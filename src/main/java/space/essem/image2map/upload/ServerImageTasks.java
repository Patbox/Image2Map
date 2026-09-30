package space.essem.image2map.upload;

import eu.pb4.mapcanvas.api.core.CanvasImage;
import eu.pb4.sgui.api.SguiUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.core.component.DataComponents;
import space.essem.image2map.FabricPermissionBridge;
import space.essem.image2map.Image2Map;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.gui.PreviewGui;
import space.essem.image2map.image.ImageFetcher;
import space.essem.image2map.image.ImageSafety;
import space.essem.image2map.network.UploadPayloads;
import space.essem.image2map.renderer.MapRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

import static space.essem.image2map.upload.ImageTaskState.Phase.*;

/** All state changes, permission checks, GUI changes and inventory writes run on the server thread. */
public final class ServerImageTasks {
    public record Options(Image2Map.DitherMode mode, int width, int height, boolean preview, boolean folder) {
        /** Preserve the original URL/server-file read and decode deadlines; folders had none. */
        public int acquisitionTimeoutSeconds() { return folder ? 0 : preview ? 30 : 20; }
    }
    private final Map<ServerPlayer, Session> sessions = new IdentityHashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private ExecutorService workers;
    private ImageFetcher fetcher;

    private static final class Session {
        final ImageTaskState state = new ImageTaskState();
        boolean clientUpload;
        long nextRejection;
        Job job;
    }

    private static final class Job {
        final UUID id;
        final CommandSourceStack source;
        final ServerPlayer player;
        final String input;
        final Options options;
        final ImageFetcher.Resources resources = new ImageFetcher.Resources();
        boolean clientUpload;
        UploadPayloads.Metadata metadata;
        UploadBuffer buffer;
        PreviewGui preview;

        Job(UUID id, CommandSourceStack source, ServerPlayer player, String input, Options options) {
            this.id = id;
            this.source = source;
            this.player = player;
            this.input = input;
            this.options = options;
        }
    }

    private Image2MapConfig config() { return Image2Map.CONFIG; }

    public Future<CanvasImage> renderPreview(BufferedImage image, Image2Map.DitherMode mode, int width, int height) {
        return workers.submit(() -> MapRenderer.render(image, mode, width, height));
    }

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().daemon().name("image2map-worker-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
            fetcher = new ImageFetcher(config().networkTimeout);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Session session : new ArrayList<>(sessions.values())) {
                if (session.job != null) dispose(session);
            }
            sessions.clear();
            cooldowns.clear();
            if (workers != null) workers.shutdownNow();
            if (fetcher != null) fetcher.close();
        });
        ServerPlayConnectionEvents.INIT.register((handler, server) -> sessions.put(handler.player, new Session()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Session session = sessions.remove(handler.player);
            if (session != null && session.job != null) dispose(session);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.nanoTime();
            cooldowns.entrySet().removeIf(entry -> now >= entry.getValue());
            for (Session session : new ArrayList<>(sessions.values())) {
                if (session.job == null) continue;
                if (session.state.expired(now)) fail(session, "Image operation timed out");
                else if (!hasPermission(session.job)) fail(session, "Image2Map permission was revoked");
                else if (session.state.phase() == PREVIEWING && (session.job.preview == null || !session.job.preview.isOpen())) {
                    finish(session, true, "Preview closed");
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(UploadPayloads.Capabilities.TYPE, (payload, context) -> {
            Session session = sessions.get(context.player());
            if (session != null) {
                session.clientUpload = payload.clientUpload();
                if (!payload.clientUpload() && session.job != null && session.job.clientUpload) {
                    fail(session, "Client no longer supports image uploads");
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(UploadPayloads.Metadata.TYPE, (payload, context) -> metadata(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(UploadPayloads.Chunk.TYPE, (payload, context) -> chunk(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(UploadPayloads.Complete.TYPE, (payload, context) -> complete(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(UploadPayloads.Cancel.TYPE, (payload, context) -> {
            Session session = matching(context.player(), payload.requestId());
            if (session != null && session.job.clientUpload) fail(session, "Client cancelled: " + payload.reason());
        });
    }

    public int start(CommandSourceStack source, ServerPlayer player, String path, Options options) {
        Session session = sessions.computeIfAbsent(player, ignored -> new Session());
        UUID id;
        try {
            if (System.nanoTime() < cooldowns.getOrDefault(player.getUUID(), Long.MIN_VALUE)) {
                throw new IOException("Please wait before starting another image operation");
            }
            id = session.state.begin(System.nanoTime());
        } catch (IOException exception) {
            source.sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
        Job job = new Job(id, source, player, ImageSafety.cleanPath(path), options);
        session.job = job;
        try {
            if (!hasPermission(job)) throw new IOException("You do not have permission to use this image operation");
            if (options.folder()) {
                if (!config().allowServerLocalFiles) throw new IOException("Server local files are disabled");
                Path folder = ImageSafety.serverPath(FabricLoader.getInstance().getGameDir(), job.input);
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Server image folder does not exist");
                transition(session, GETTING_IMAGE);
                loadFolder(session, job, folder);
            } else if (ImageSafety.isHttp(job.input)) {
                transition(session, GETTING_IMAGE);
                submit(session, job, () -> loaded(session, job, fetcher.download(job.input, config().imageLimits(), job.resources)));
            } else {
                Path serverFile = null;
                if (config().allowServerLocalFiles && !job.input.isEmpty()) {
                    try { serverFile = ImageSafety.serverPath(FabricLoader.getInstance().getGameDir(), job.input); }
                    catch (IOException ignored) { /* Unsafe server paths can still be selected on the client. */ }
                }
                if (serverFile != null && Files.exists(serverFile, LinkOption.NOFOLLOW_LINKS)) {
                    Path file = serverFile;
                    transition(session, GETTING_IMAGE);
                    submit(session, job, () -> loaded(session, job, ImageFetcher.file(file, config().imageLimits(), job.resources)));
                } else {
                    if (!config().allowClientUploadFiles) throw new IOException("Client image uploads are disabled on this server");
                    if (!session.clientUpload || !ServerPlayNetworking.canSend(player, UploadPayloads.Request.TYPE)
                            || !ServerPlayNetworking.canSend(player, UploadPayloads.Status.TYPE)) {
                        throw new IOException("Your client does not support local image uploads; provide an HTTP/HTTPS URL");
                    }
                    job.clientUpload = true;
                    transition(session, CLIENT_SELECTING_IMAGE);
                    ServerPlayNetworking.send(player, new UploadPayloads.Request(id, job.input, options.mode().name(),
                            config().imageLimits(), config().networkTimeout));
                }
            }
            source.sendSuccess(() -> Component.literal(job.clientUpload ? "Select and confirm an image on your client..." : "Getting image..."), false);
            return 1;
        } catch (Exception exception) {
            fail(session, message(exception));
            return 0;
        }
    }

    private boolean hasPermission(Job job) {
        ServerPlayer player = job.player;
        return FabricPermissionBridge.checkPermission(player, permission("use"), PermissionLevel.byId(config().minPermLevel))
                && (job.options.folder()
                ? FabricPermissionBridge.checkPermission(player, permission("createfolder"), PermissionLevel.ADMINS)
                : FabricPermissionBridge.checkPermission(player, permission(job.options.preview() ? "preview" : "create"), true));
    }

    private static Identifier permission(String name) { return Identifier.fromNamespaceAndPath("image2map", name); }
    private void transition(Session session, ImageTaskState.Phase phase) {
        Job job = session.job;
        int timeout = phase == GETTING_IMAGE && !job.options.folder()
                ? job.clientUpload ? config().networkTimeout : job.options.acquisitionTimeoutSeconds() : 0;
        session.state.transition(phase, System.nanoTime(), timeout);
    }

    private Session matching(ServerPlayer player, UUID id) {
        Session session = sessions.get(player);
        if (session == null || session.job == null || !session.state.matches(id)) {
            // Reject stale/replayed requests without aborting a different active task or amplifying floods.
            long now = System.nanoTime();
            if (session != null && now >= session.nextRejection && ServerPlayNetworking.canSend(player, UploadPayloads.Status.TYPE)) {
                session.nextRejection = now + 1_000_000_000L;
                ServerPlayNetworking.send(player, new UploadPayloads.Status(id, false, true, "Unknown image requestId"));
            }
            return null;
        }
        if (!session.job.clientUpload || !config().allowClientUploadFiles || !hasPermission(session.job)) {
            fail(session, "Client upload is not allowed");
            return null;
        }
        if (session.state.expired(System.nanoTime())) {
            fail(session, "Image operation timed out");
            return null;
        }
        return session;
    }

    private void metadata(ServerPlayer player, UploadPayloads.Metadata payload) {
        Session session = matching(player, payload.requestId());
        if (session == null) return;
        try {
            if (session.state.phase() != CLIENT_SELECTING_IMAGE) throw new IOException("Image metadata is not expected in this state");
            ImageSafety.validateInfo(payload.info(), payload.byteSize(), config().imageLimits());
            if (!payload.mode().equals(session.job.options.mode().name())) throw new IOException("Dither mode does not match the request");
            session.job.buffer = new UploadBuffer(payload.byteSize(), config().imageLimits().maxBytes());
            session.job.metadata = payload;
            transition(session, GETTING_IMAGE);
            ServerPlayNetworking.send(player, new UploadPayloads.Status(payload.requestId(), true, false, "Ready to receive image"));
        } catch (Exception exception) { fail(session, message(exception)); }
    }

    private void chunk(ServerPlayer player, UploadPayloads.Chunk payload) {
        Session session = matching(player, payload.requestId());
        if (session == null) return;
        try {
            if (session.state.phase() != GETTING_IMAGE || session.job.buffer == null) throw new IOException("Image chunks are not expected in this state");
            session.job.buffer.append(payload.sequence(), payload.bytes());
        } catch (Exception exception) { fail(session, message(exception)); }
    }

    private void complete(ServerPlayer player, UploadPayloads.Complete payload) {
        Session session = matching(player, payload.requestId());
        if (session == null) return;
        try {
            Job job = session.job;
            if (session.state.phase() != GETTING_IMAGE || job.buffer == null) throw new IOException("Image completion is not expected in this state");
            byte[] data = job.buffer.complete();
            job.buffer = null;
            loaded(session, job, data);
        } catch (Exception exception) { fail(session, message(exception)); }
    }

    private boolean active(Session session, Job job) {
        return sessions.get(job.player) == session && session.job == job && session.state.matches(job.id)
                && !job.player.hasDisconnected() && !session.state.expired(System.nanoTime());
    }

    private void loaded(Session session, Job job, byte[] data) {
        job.source.getServer().execute(() -> {
            if (!active(session, job)) return;
            submit(session, job, () -> {
                // Decoding was part of getImage's timeout in the original implementation.
                var decoded = ImageSafety.decode(data, config().imageLimits());
                if (job.metadata != null) ImageSafety.verifyDeclaration(job.metadata.info(), decoded.info());
                job.source.getServer().execute(() -> process(session, job, decoded.image()));
            });
        });
    }

    private void process(Session session, Job job, BufferedImage image) {
        if (!active(session, job)) { image.flush(); return; }
        try {
            if (!hasPermission(job)) throw new IOException("Image2Map permission was revoked");
            transition(session, PROCESSING_IMAGE);
            job.source.sendSuccess(() -> Component.literal("Converting into maps..."), false);
            int[] size = outputSize(image, job.options);
            if (job.options.preview()) {
                transition(session, PREVIEWING);
                job.preview = new PreviewGui(job.player, image, job.input, job.options.mode(), size[0], size[1],
                        () -> { if (active(session, job)) finish(session, true, "Preview closed"); });
                status(job, true, true, "Image received; preview opened");
            } else {
                submit(session, job, () -> {
                    CanvasImage rendered;
                    try { rendered = MapRenderer.render(image, job.options.mode(), size[0], size[1]); }
                    finally { image.flush(); }
                    job.source.getServer().execute(() -> {
                        if (!active(session, job)) return;
                        try {
                            if (!hasPermission(job)) throw new IOException("Image2Map permission was revoked");
                            Image2Map.giveToPlayer(job.player, MapRenderer.toVanillaItems(rendered, job.player.level(), job.input), job.input, size[0], size[1]);
                            finish(session, true, "Done!");
                        } catch (Exception exception) { fail(session, message(exception)); }
                    });
                });
            }
        } catch (Exception exception) { image.flush(); fail(session, message(exception)); }
    }

    private int[] outputSize(BufferedImage image, Options options) throws IOException {
        int width = options.width(), height = options.height();
        if (width == 0 || height == 0) {
            double scale = Math.min(1, Math.min(config().imageMaxWidthHeight / (double) image.getWidth(),
                    config().imageMaxWidthHeight / (double) image.getHeight()));
            width = Math.max(1, (int) (image.getWidth() * scale));
            height = Math.max(1, (int) (image.getHeight() * scale));
        }
        if (width < 1 || height < 1 || width > config().imageMaxWidthHeight || height > config().imageMaxWidthHeight) {
            throw new IOException("Map output dimensions exceed the configured limit");
        }
        return new int[] { width, height };
    }

    private void loadFolder(Session session, Job job, Path folder) {
        submit(session, job, () -> {
            // Keep the existing bulk command under the same per-player task guard.
            ArrayList<Path> files = new ArrayList<>();
            try (var paths = Files.walk(folder)) {
                var iterator = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
                while (iterator.hasNext()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Folder operation cancelled");
                    files.add(iterator.next());
                }
            }
            job.source.getServer().execute(() -> {
                if (!active(session, job)) return;
                transition(session, PROCESSING_IMAGE);
                submit(session, job, () -> {
                    record Rendered(CanvasImage image, int width, int height) { }
                    var images = new ArrayList<Rendered>();
                    for (Path file : files) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Folder operation cancelled");
                        Path safe = ImageSafety.serverPath(FabricLoader.getInstance().getGameDir(),
                                FabricLoader.getInstance().getGameDir().toRealPath().relativize(file).toString());
                        ImageSafety.Decoded decoded;
                        int[] size;
                        try {
                            decoded = ImageSafety.decode(ImageFetcher.file(safe, config().imageLimits(), job.resources), config().imageLimits());
                            size = outputSize(decoded.image(), job.options);
                        } catch (IOException exception) {
                            Image2Map.LOGGER.debug("Skipping invalid image in folder: {}", file, exception);
                            continue;
                        }
                        images.add(new Rendered(MapRenderer.render(decoded.image(), job.options.mode(), size[0], size[1]), size[0], size[1]));
                        decoded.image().flush();
                    }
                    job.source.getServer().execute(() -> {
                        if (!active(session, job)) return;
                        try {
                            if (!hasPermission(job)) throw new IOException("Image2Map permission was revoked");
                            if (images.isEmpty()) throw new IOException("Folder contains no valid images");
                            var items = images.stream().map(image -> Image2Map.toSingleStack(
                                    MapRenderer.toVanillaItems(image.image(), job.player.level(), job.input), job.input, image.width(), image.height())).toList();
                            ItemStack bundle = new ItemStack(Items.BUNDLE);
                            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(items));
                            job.player.addItem(bundle);
                            finish(session, true, "Done!");
                        } catch (Exception exception) { fail(session, message(exception)); }
                    });
                });
            });
        });
    }

    @FunctionalInterface private interface Work { void run() throws Exception; }
    private void submit(Session session, Job job, Work work) {
        try {
            job.resources.track(workers.submit(() -> {
                try { work.run(); }
                catch (Exception exception) {
                    job.source.getServer().execute(() -> { if (active(session, job)) fail(session, message(exception)); });
                }
            }));
        } catch (RuntimeException exception) { fail(session, "Image worker queue is full; please try again later"); }
    }

    private static String message(Throwable exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) value = exception.getClass().getSimpleName();
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    private void status(Job job, boolean success, boolean terminal, String message) {
        if (job.clientUpload && !job.player.hasDisconnected() && ServerPlayNetworking.canSend(job.player, UploadPayloads.Status.TYPE)) {
            ServerPlayNetworking.send(job.player, new UploadPayloads.Status(job.id, success, terminal,
                    message.length() > 512 ? message.substring(0, 512) : message));
        }
    }

    private void fail(Session session, String reason) { finish(session, false, reason); }
    private void finish(Session session, boolean success, String message) {
        Job job = session.job;
        if (job == null) return;
        try {
            status(job, success, true, message);
            // Client upload status is displayed by the client; avoid duplicate error messages.
            if (!job.clientUpload || success) {
                job.source.sendSuccess(() -> Component.literal(message).withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            }
        } finally { dispose(session); }
    }

    private void dispose(Session session) {
        Job job = session.job;
        session.job = null; // Invalidate callbacks before closing a preview.
        session.state.finish(System.nanoTime(), config().imageOperationCooldownSeconds);
        cooldowns.put(job.player.getUUID(), System.nanoTime() + config().imageOperationCooldownSeconds * 1_000_000_000L);
        job.resources.close();
        job.buffer = null;
        PreviewGui preview = job.preview;
        // A GUI constructor can fail after opening its base GUI but before returning its instance.
        if (preview == null && job.options.preview() && SguiUtils.getCurrentGui(job.player) instanceof PreviewGui opened) preview = opened;
        if (preview != null && preview.isOpen()) {
            try { preview.close(); }
            catch (RuntimeException exception) { Image2Map.LOGGER.warn("Failed to close an image preview", exception); }
        }
    }
}
