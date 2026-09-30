package space.essem.image2map.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import space.essem.image2map.image.ImageFetcher;
import space.essem.image2map.image.ImageSafety;
import space.essem.image2map.network.UploadPayloads;
import space.essem.image2map.upload.UploadBuffer;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loaded exclusively through the client entrypoint and client source set. */
public final class Image2MapClient implements ClientModInitializer {
    private final ExecutorService reader = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            Thread.ofPlatform().daemon().name("image2map-client-reader").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean pickerOpen = new AtomicBoolean();
    private Pending pending;

    private static final class Pending {
        final UploadPayloads.Request request;
        final ClientPacketListener connection;
        final Screen previous;
        final ImageFetcher.Resources resources = new ImageFetcher.Resources();
        final ImageSafety.Limits limits;
        long deadline = Long.MAX_VALUE;
        byte[] bytes;
        int offset;
        int sequence;
        boolean accepted;
        UploadProgressScreen progress;
        UploadConfirmScreen confirmation;

        Pending(UploadPayloads.Request request, Minecraft client) {
            this.request = request;
            this.connection = client.getConnection();
            this.previous = client.gui.screen();
            // A malicious server cannot lift local safety limits.
            this.limits = new ImageSafety.Limits(Math.min(request.limits().maxBytes(), 64 * 1024 * 1024L),
                    request.limits().formats());
        }
        void startNetworkTimeout() { deadline = System.nanoTime() + request.timeoutSeconds() * 1_000_000_000L; }
    }

    @Override public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (pending != null) end(pending, null, false);
            reader.shutdownNow();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ClientPlayNetworking.canSend(UploadPayloads.Capabilities.TYPE)) {
                ClientPlayNetworking.send(new UploadPayloads.Capabilities(true));
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (pending != null) end(pending, "Disconnected during image upload", false);
        });
        ClientPlayNetworking.registerGlobalReceiver(UploadPayloads.Request.TYPE, (request, context) -> request(request));
        ClientPlayNetworking.registerGlobalReceiver(UploadPayloads.Status.TYPE, (status, context) -> status(status));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void request(UploadPayloads.Request request) {
        Minecraft client = Minecraft.getInstance();
        if (pending != null) {
            sendCancel(request, "Another image upload is already pending");
            return;
        }
        if (request.timeoutSeconds() < 1 || request.timeoutSeconds() > 3600 || request.limits().maxBytes() < 1
                || request.limits().formats().isEmpty() || request.limits().formats().size() > 32
                || !ClientPlayNetworking.canSend(UploadPayloads.Metadata.TYPE) || !ClientPlayNetworking.canSend(UploadPayloads.Chunk.TYPE)
                || !ClientPlayNetworking.canSend(UploadPayloads.Complete.TYPE) || !ClientPlayNetworking.canSend(UploadPayloads.Cancel.TYPE)) {
            sendCancel(request, "Invalid upload request or missing protocol channels");
            return;
        }
        Pending task = new Pending(request, client);
        pending = task;
        if (request.path().isBlank()) select(task);
        else confirm(task, ImageSafety.cleanPath(request.path()));
    }

    private boolean active(Pending task) {
        return pending == task && Minecraft.getInstance().getConnection() == task.connection;
    }

    private void progress(Pending task, Component message) {
        Minecraft client = Minecraft.getInstance();
        task.progress = new UploadProgressScreen(() -> end(task, "Image upload cancelled", true));
        task.progress.message(message);
        client.gui.setScreen(task.progress);
    }

    private void select(Pending task) {
        if (!pickerOpen.compareAndSet(false, true)) {
            end(task, "Close the previous image selection dialog first", true);
            return;
        }
        progress(task, Component.translatable("image2map.upload.selecting"));
        // Native dialogs must not block Minecraft's render/network thread. One may be open at a time.
        Thread.ofPlatform().daemon().name("image2map-file-picker").start(() -> {
            try {
                String selected = TinyFileDialogs.tinyfd_openFileDialog("Image2Map: Select an image", "", null, "Image file", false);
                Minecraft.getInstance().execute(() -> {
                    if (!active(task)) return;
                    if (selected == null || selected.isBlank()) end(task, "Image selection cancelled", true);
                    else confirm(task, selected);
                });
            } catch (Throwable exception) {
                Minecraft.getInstance().execute(() -> { if (active(task)) end(task, "Could not open image selection dialog", true); });
            } finally { pickerOpen.set(false); }
        });
    }

    private void confirm(Pending task, String path) {
        if (!active(task)) return;
        if (task.progress != null) task.progress.resolve();
        task.confirmation = new UploadConfirmScreen(path, confirmed -> {
            if (!active(task)) return;
            if (!confirmed) { end(task, "Image upload cancelled", true); return; }
            // File contents are read only after explicit user confirmation.
            progress(task, Component.translatable("image2map.upload.reading"));
            try {
                task.resources.track(reader.submit(() -> {
                    try {
                        Path file = Path.of(path).toAbsolutePath().normalize(); // Relative to the process working directory.
                        byte[] bytes = ImageFetcher.file(file, task.limits, task.resources);
                        // Decode once locally to reject corrupt/non-image files before sending any data.
                        var decoded = ImageSafety.decode(bytes, task.limits);
                        var info = decoded.info();
                        decoded.image().flush();
                        Minecraft.getInstance().execute(() -> {
                            if (!active(task)) return;
                            task.bytes = bytes;
                            task.startNetworkTimeout();
                            ClientPlayNetworking.send(new UploadPayloads.Metadata(task.request.requestId(), info.format(), bytes.length,
                                    info.width(), info.height(), task.request.mode()));
                        });
                    } catch (Exception exception) {
                        Minecraft.getInstance().execute(() -> {
                            if (active(task)) end(task, "Could not read image: " + error(exception), true);
                        });
                    }
                }));
            } catch (RuntimeException exception) {
                end(task, "Image reader is busy; please try again later", true);
            }
        });
        Minecraft.getInstance().gui.setScreen(task.confirmation);
    }

    private void status(UploadPayloads.Status status) {
        Pending task = pending;
        if (task == null || !active(task) || !task.request.requestId().equals(status.requestId())) return;
        if (status.terminal()) {
            end(task, status.accepted() ? null : status.message(), false);
        } else if (status.accepted() && task.bytes != null && !task.accepted) {
            task.accepted = true;
            task.startNetworkTimeout();
        } else end(task, "Unexpected image upload response", true);
    }

    private void tick(Minecraft client) {
        Pending task = pending;
        if (task == null) return;
        if (!active(task)) { end(task, "Disconnected during image upload", false); return; }
        if (System.nanoTime() >= task.deadline) { end(task, "Image upload timed out", true); return; }
        if (!task.accepted || task.bytes == null) return;
        // Pace uploads to four 16 KiB chunks per tick instead of flooding the connection.
        for (int count = 0; count < 4 && task.offset < task.bytes.length; count++) {
            int end = Math.min(task.offset + UploadBuffer.CHUNK_SIZE, task.bytes.length);
            ClientPlayNetworking.send(new UploadPayloads.Chunk(task.request.requestId(), task.sequence++, Arrays.copyOfRange(task.bytes, task.offset, end)));
            task.offset = end;
        }
        task.progress.message(Component.translatable("image2map.upload.progress", task.offset * 100L / task.bytes.length));
        if (task.offset == task.bytes.length) {
            ClientPlayNetworking.send(new UploadPayloads.Complete(task.request.requestId()));
            task.bytes = null;
            // Receiving and rendering the image may outlive the network transfer.
            task.deadline = Long.MAX_VALUE;
            task.progress.message(Component.translatable("image2map.upload.processing"));
        }
    }

    private void end(Pending task, String message, boolean notifyServer) {
        if (pending != task) return;
        boolean connected = active(task);
        pending = null;
        task.resources.close();
        task.bytes = null;
        if (notifyServer && connected) sendCancel(task.request, message == null ? "Cancelled" : message);
        if (task.progress != null) task.progress.resolve();
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() == task.progress || client.gui.screen() == task.confirmation) client.gui.setScreen(connected ? task.previous : null);
        if (message != null && client.player != null) client.player.sendSystemMessage(Component.literal("Image2Map: " + message));
    }

    private static void sendCancel(UploadPayloads.Request request, String message) {
        if (ClientPlayNetworking.canSend(UploadPayloads.Cancel.TYPE)) {
            ClientPlayNetworking.send(new UploadPayloads.Cancel(request.requestId(), message.length() > 512 ? message.substring(0, 512) : message));
        }
    }

    private static String error(Exception exception) {
        String message = exception.getMessage();
        if (message == null) return exception.getClass().getSimpleName();
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
