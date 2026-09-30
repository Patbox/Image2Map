package space.essem.image2map;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import com.sun.net.httpserver.HttpServer;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import space.essem.image2map.config.ConfigMigration;
import space.essem.image2map.config.Image2MapConfig;
import space.essem.image2map.image.ImageFetcher;
import space.essem.image2map.image.ImageSafety;
import space.essem.image2map.upload.ImageTaskState;
import space.essem.image2map.upload.ServerImageTasks;
import space.essem.image2map.upload.UploadBuffer;
import space.essem.image2map.network.UploadPayloads;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static space.essem.image2map.upload.ImageTaskState.Phase.*;

/** Run via gradlew securityTest/check; failures throw AssertionError without requiring a game. */
public final class SecurityRegressionTest {
    private static int assertions;
    private static final ImageSafety.Limits LIMITS = new ImageSafety.Limits(1024 * 1024, List.of("png", "jpeg", "gif", "bmp"));

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        configMigration();
        paths(root);
        images(root);
        uploads();
        lifecycle();
        cancellation();
        commands();
        protocol();
        http();
        System.out.println("Image2Map security regression checks passed: " + assertions);
    }

    private static void configMigration() throws Exception {
        var old = JsonParser.parseString("{\"allowLocalFiles\":true,\"maxSize\":1234,\"minPermLevel\":1}").getAsJsonObject();
        ConfigMigration.migrate(old);
        check(old.get("version").getAsInt() == 2, "Legacy version migration");
        check(!old.has("maxSize") && !old.has("allowLocalFiles"), "Legacy names removed");
        Image2MapConfig config = Image2MapConfig.fromJson(old.toString());
        check(config.allowServerLocalFiles && config.imageMaxWidthHeight == 1234 && config.minPermLevel == 1, "Legacy values preserved");
        check(config.allowClientUploadFiles && config.networkTimeout == 30 && config.imageOperationCooldownSeconds == 5, "New defaults");
        check(config.imageLimits().maxBytes() == 20 * 1024 * 1024L, "Default image size is 20 MiB");
        check(config.imageLimits().maxBytes() == config.imageFileMaxSize * 1024L, "KiB conversion");
        check(Image2MapConfig.fromJson("{\"version\":2,\"imageMaxWidthHeight\":99}").imageMaxWidthHeight == 99, "Version 2 preserved");
        rejects(() -> Image2MapConfig.fromJson("{\"version\":3}"), "Unsupported config version");
        rejects(() -> Image2MapConfig.fromJson("{\"version\":2,\"networkTimeout\":0}"), "Invalid timeout");
        rejects(() -> Image2MapConfig.fromJson("{\"version\":2,\"imageFileMaxSize\":-1}"), "Invalid file budget");
        rejects(() -> Image2MapConfig.fromJson("{\"version\":2,\"allowedImageFormats\":null}"), "Missing format limits");
        var mixed = Image2MapConfig.fromJson("{\"maxSize\":111,\"imageMaxWidthHeight\":222}");
        check(mixed.imageMaxWidthHeight == 222, "New field takes precedence during migration");
    }

    private static void paths(Path root) throws Exception {
        check(ImageSafety.cleanPath("  \"some image.png\"  ").equals("some image.png"), "Quoted path");
        check(ImageSafety.cleanPath("''").isEmpty() && ImageSafety.cleanPath(null).isEmpty(), "Empty picker path");
        check(ImageSafety.cleanPath("'a b.png'").equals("a b.png"), "Single quotes");
        check(ImageSafety.isHttp("HTTPS://example.com/x") && !ImageSafety.isHttp("file:///x"), "Only HTTP/HTTPS");
        Path safe = ImageSafety.serverPath(root, "images/a.png");
        check(safe.startsWith(root.toRealPath()), "Relative server path confined");
        for (String input : List.of("../x", "images/../../x", "images\\..\\x", "/etc/passwd", "C:\\x", "C:x", "\\\\host\\share", "", "a\u0000b")) {
            rejects(() -> ImageSafety.serverPath(root, input), "Reject server traversal or absolute path " + input);
        }
        Path link = root.resolve("link");
        try {
            if (!Files.exists(link)) Files.createSymbolicLink(link, root.toRealPath());
            rejects(() -> ImageSafety.serverPath(root, "link/x.png"), "Reject server symlink");
        } catch (IOException | UnsupportedOperationException exception) {
            System.out.println("Symlink check skipped: platform does not allow symlink creation");
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return bytes.toByteArray();
    }

    private static void images(Path root) throws Exception {
        byte[] bytes = png(40, 25);
        var decoded = ImageSafety.decode(bytes, LIMITS);
        check(decoded.info().format().equals("png") && decoded.image().getWidth() == 40 && decoded.image().getHeight() == 25, "Actual image format/dimensions");
        ImageSafety.verifyDeclaration(new ImageSafety.Info("PNG", 40, 25), decoded.info());
        rejects(() -> ImageSafety.verifyDeclaration(new ImageSafety.Info("png", 40, 26), decoded.info()), "Spoofed dimension metadata");
        rejects(() -> ImageSafety.verifyDeclaration(new ImageSafety.Info("jpeg", 40, 25), decoded.info()), "Spoofed format metadata");
        rejects(() -> ImageSafety.decode("not an image".getBytes(), LIMITS), "Non-image content");
        rejects(() -> ImageSafety.decode(bytes, new ImageSafety.Limits(1, List.of("png"))), "Encoded byte limit");
        rejects(() -> ImageSafety.decode(bytes, new ImageSafety.Limits(100000, List.of("jpeg"))), "Content format allowlist");
        rejects(() -> ImageSafety.decode(Arrays.copyOf(bytes, 24), LIMITS), "Truncated image");
        var wide = ImageSafety.decode(png(20000, 1), LIMITS);
        check(wide.info().width() == 20000, "Wide source image accepted within file size limit");
        wide.image().flush();
        ImageSafety.validateInfo(new ImageSafety.Info("png", 100000, 100000), bytes.length, LIMITS);
        rejects(() -> ImageSafety.validateInfo(new ImageSafety.Info("png", 0, 25), bytes.length, LIMITS), "Invalid source dimensions");
        check(ImageSafety.normalizeFormat("JPG").equals("jpeg"), "JPEG aliases");
        Path fakeExtension = root.resolve("actually-png.txt");
        Files.write(fakeExtension, bytes);
        try (var resources = new ImageFetcher.Resources()) {
            check(ImageSafety.decode(ImageFetcher.file(fakeExtension, LIMITS, resources), LIMITS).info().format().equals("png"), "Extension not trusted");
        }
        try (var resources = new ImageFetcher.Resources()) {
            rejects(() -> ImageFetcher.file(root, LIMITS, resources), "Directory upload rejected");
        }
        check(Arrays.equals(bytes, ImageSafety.readBounded(new ByteArrayInputStream(bytes), bytes.length)), "Stream exact limit");
        rejects(() -> ImageSafety.readBounded(new ByteArrayInputStream(bytes), bytes.length - 1), "Stream oversized without Content-Length");
        rejects(() -> ImageSafety.readBounded(new ByteArrayInputStream(new byte[0]), 100), "Empty stream");
    }

    private static void uploads() throws Exception {
        byte[] bytes = new byte[UploadBuffer.CHUNK_SIZE + 3];
        UploadBuffer upload = new UploadBuffer(bytes.length, bytes.length);
        upload.append(0, Arrays.copyOfRange(bytes, 0, UploadBuffer.CHUNK_SIZE));
        upload.append(1, Arrays.copyOfRange(bytes, UploadBuffer.CHUNK_SIZE, bytes.length));
        check(Arrays.equals(bytes, upload.complete()), "16KiB multi-chunk reassembly");
        rejects(() -> new UploadBuffer(0, 100), "Empty metadata");
        rejects(() -> new UploadBuffer(101, 100), "Oversized metadata");
        rejects(() -> new UploadBuffer(10, 100).append(1, new byte[5]), "Out-of-order chunk");
        rejects(() -> new UploadBuffer(10, 100).append(0, new byte[0]), "Empty chunk");
        rejects(() -> new UploadBuffer(100000, 100000).append(0, new byte[UploadBuffer.CHUNK_SIZE + 1]), "Chunk limit");
        rejects(() -> new UploadBuffer(10, 100).append(0, new byte[11]), "Declared size exceeded");
        UploadBuffer replay = new UploadBuffer(10, 100);
        replay.append(0, new byte[5]);
        rejects(() -> replay.append(0, new byte[5]), "Duplicate chunk");
        rejects(replay::complete, "Incomplete upload");
    }

    private static void lifecycle() throws Exception {
        ImageTaskState state = new ImageTaskState();
        check(state.phase() == IDLE, "New connection starts idle");
        UUID id = state.begin(0);
        state.transition(CLIENT_SELECTING_IMAGE, 0, 30);
        check(state.matches(id) && !state.matches(UUID.randomUUID()), "RequestId matching");
        long hoursLater = TimeUnit.HOURS.toNanos(8);
        check(!state.expired(hoursLater), "Selection and confirmation can take hours");
        rejects(() -> state.begin(1), "Concurrent task blocked");
        rejects(() -> state.begin(hoursLater), "Long selection still reserves the task");
        rejects(() -> state.transition(PROCESSING_IMAGE, 1, 30), "Cannot bypass metadata acceptance");
        state.transition(GETTING_IMAGE, hoursLater, 30);
        check(!state.expired(hoursLater + TimeUnit.SECONDS.toNanos(30) - 1)
                && state.expired(hoursLater + TimeUnit.SECONDS.toNanos(30)), "Acquisition timing starts after selection");
        state.transition(PROCESSING_IMAGE, hoursLater + 1, 30);
        check(!state.expired(hoursLater * 2), "Rendering has no acquisition or network deadline");
        state.transition(PREVIEWING, hoursLater + 2, 30);
        rejects(() -> state.begin(4), "Preview reserves task");
        check(!state.expired(100000000000L), "Idle preview remains open");
        state.finish(10, 5);
        check(state.phase() == IDLE && !state.matches(id), "Preview close invalidates requestId");
        rejects(() -> state.begin(5000000009L), "Cooldown enforced");
        UUID next = state.begin(5000000010L);
        check(!id.equals(next), "RequestId regenerated");
        state.transition(GETTING_IMAGE, 5000000010L, 30);
        check(!state.expired(35000000009L) && state.expired(35000000010L), "Upload acquisition deadline enforced");
        state.finish(35000000010L, 0);
        state.begin(35000000010L);
        state.transition(GETTING_IMAGE, 35000000010L, 30);
        state.finish(35000000011L, 0);
        check(state.phase() == IDLE, "Timeout/failure permits a new job");
        for (var phase : List.of(CLIENT_SELECTING_IMAGE, GETTING_IMAGE, PROCESSING_IMAGE, PREVIEWING)) {
            state.begin(100000000000L);
            if (phase == CLIENT_SELECTING_IMAGE) state.transition(phase, 100000000000L, 30);
            else {
                state.transition(GETTING_IMAGE, 100000000000L, 30);
                if (phase == PROCESSING_IMAGE || phase == PREVIEWING) state.transition(PROCESSING_IMAGE, 100000000000L, 30);
                if (phase == PREVIEWING) state.transition(PREVIEWING, 100000000000L, 30);
            }
            state.finish(100000000000L, 0);
            check(state.phase() == IDLE && state.requestId() == null, "Cancel/disconnect resets " + phase);
        }
        for (boolean preview : List.of(false, true)) {
            var options = new ServerImageTasks.Options(Image2Map.DitherMode.NONE, 0, 0, preview, false);
            int seconds = options.acquisitionTimeoutSeconds();
            check(seconds == (preview ? 30 : 20), "Original acquisition deadline for " + (preview ? "preview" : "create"));
            var acquisition = new ImageTaskState();
            acquisition.begin(0);
            acquisition.transition(GETTING_IMAGE, 0, seconds);
            long boundary = TimeUnit.SECONDS.toNanos(seconds);
            check(!acquisition.expired(boundary - 1) && acquisition.expired(boundary), "Reading and decoding share the original deadline");
            acquisition.transition(PROCESSING_IMAGE, boundary - 1, seconds);
            check(!acquisition.expired(hoursLater), "Rendering can exceed the original acquisition deadline");
        }
        var folder = new ServerImageTasks.Options(Image2Map.DitherMode.NONE, 0, 0, false, true);
        check(folder.acquisitionTimeoutSeconds() == 0, "Folder retains its original lack of acquisition timeout");
        var folderState = new ImageTaskState();
        folderState.begin(0);
        folderState.transition(GETTING_IMAGE, 0, folder.acquisitionTimeoutSeconds());
        check(!folderState.expired(hoursLater), "Folder traversal has no deadline");
        folderState.transition(PROCESSING_IMAGE, hoursLater, 30);
        check(!folderState.expired(hoursLater * 2), "Folder decoding and rendering have no deadline");
    }

    private static void cancellation() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        var stream = new ByteArrayInputStream(new byte[] { 1 }) {
            @Override public void close() { closed.set(true); }
        };
        var future = new FutureTask<>(() -> null);
        var resources = new ImageFetcher.Resources();
        resources.track(stream);
        resources.track(future);
        resources.close();
        check(closed.get() && future.isCancelled(), "Abort releases stream and worker");
        var late = new FutureTask<>(() -> null);
        resources.track(late);
        check(late.isCancelled(), "Late worker cannot revive aborted task");
        rejects(() -> resources.track(new ByteArrayInputStream(new byte[] { 1 })), "Late stream closed after abort");
        resources.close();
    }

    private static CommandNode<CommandSourceStack> unrestricted(CommandNode<CommandSourceStack> node) {
        var copy = node.createBuilder().requires(source -> true).build();
        for (var child : node.getChildren()) copy.addChild(unrestricted(child));
        return copy;
    }

    private static void commands() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        // Parsing exercises the real registered syntax; permission behavior needs a live player.
        dispatcher.getRoot().addChild(unrestricted(ImageCommands.root(new Image2MapConfig()).build()));
        for (String input : List.of("image2map create", "image2map create none", "image2map create dither",
                "image2map create 128 256 none", "image2map preview", "image2map preview https://example.com/a.png",
                "image2map create none https://example.com/a.png", "image2map create 128 256 dither https://example.com/a.png",
                "image2map create none \"C:\\my images\\a.png\"", "image2map create-folder none images")) {
            var parsed = dispatcher.parse(input, null);
            check(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty() && parsed.getContext().build(input).getCommand() != null,
                    "Executable command syntax: " + input);
        }
        String input = "image2map create none \"C:\\my images\\a.png\"";
        var parsed = dispatcher.parse(input, null).getContext().build(input);
        check(ImageSafety.cleanPath(StringArgumentType.getString(parsed, "path")).equals("C:\\my images\\a.png"), "Greedy quoted Windows path preserved");
        var oversized = dispatcher.parse("image2map create 99999 128 none", null).getContext().build("image2map create 99999 128 none");
        rejects(() -> Image2Map.DitherMode.fromString(StringArgumentType.getString(oversized, "mode")),
                "Out-of-range dimensions cannot become a valid mode-only command");
        check(dispatcher.parse("image2map create-folder none", null).getContext().build("image2map create-folder none").getCommand() == null,
                "Folder command still requires a server path");
    }

    private static <T> T roundTrip(StreamCodec<FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, value);
            T decoded = codec.decode(buffer);
            check(!buffer.isReadable(), "Payload fully consumed");
            return decoded;
        } finally { buffer.release(); }
    }

    private static void protocol() throws Exception {
        UUID id = UUID.randomUUID();
        var caps = new UploadPayloads.Capabilities(false);
        check(roundTrip(UploadPayloads.Capabilities.CODEC, caps).equals(caps), "Unsupported capability preserved");
        var request = new UploadPayloads.Request(id, "C:\\my image.png", "NONE", LIMITS, 30);
        check(roundTrip(UploadPayloads.Request.CODEC, request).equals(request), "Request round trip");
        var metadata = new UploadPayloads.Metadata(id, "png", 1234, 40, 25, "NONE");
        check(roundTrip(UploadPayloads.Metadata.CODEC, metadata).equals(metadata), "Metadata round trip");
        var chunk = roundTrip(UploadPayloads.Chunk.CODEC, new UploadPayloads.Chunk(id, 2, new byte[UploadBuffer.CHUNK_SIZE]));
        check(chunk.requestId().equals(id) && chunk.sequence() == 2 && chunk.bytes().length == UploadBuffer.CHUNK_SIZE, "Chunk round trip and identity");
        var complete = new UploadPayloads.Complete(id);
        check(roundTrip(UploadPayloads.Complete.CODEC, complete).equals(complete), "Completion round trip");
        var cancel = new UploadPayloads.Cancel(id, "cancelled");
        check(roundTrip(UploadPayloads.Cancel.CODEC, cancel).equals(cancel), "Cancellation round trip");
        var status = new UploadPayloads.Status(id, false, true, "failed");
        check(roundTrip(UploadPayloads.Status.CODEC, status).equals(status), "Result round trip");
        FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.buffer());
        try {
            oversized.writeUUID(id).writeVarInt(0).writeByteArray(new byte[UploadBuffer.CHUNK_SIZE + 1]);
            rejects(() -> UploadPayloads.Chunk.CODEC.decode(oversized), "Oversized chunk rejected at wire boundary");
        } finally { oversized.release(); }
    }

    private static void http() throws Exception {
        byte[] png = png(20, 10);
        var limits = new ImageSafety.Limits(1024, List.of("png"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        server.setExecutor(handlers);
        CountDownLatch stalled = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
        server.createContext("/valid", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
            }
        });
        server.createContext("/oversized", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 2048);
                exchange.getResponseBody().write(new byte[2048]);
            }
        });
        server.createContext("/chunked", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[2048]);
            }
        });
        server.createContext("/error", exchange -> { try (exchange) { exchange.sendResponseHeaders(404, -1); } });
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Location", "/valid");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/stall", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                stalled.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        ImageFetcher fetcher = new ImageFetcher(2);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            try (var resources = new ImageFetcher.Resources()) {
                check(Arrays.equals(png, fetcher.download(base + "/valid", limits, resources)), "Real HTTP image download");
            }
            try (var resources = new ImageFetcher.Resources()) {
                check(Arrays.equals(png, fetcher.download(base + "/redirect", limits, resources)), "HTTP redirect preserved");
            }
            for (String route : List.of("/oversized", "/chunked", "/error")) {
                try (var resources = new ImageFetcher.Resources()) {
                    rejects(() -> fetcher.download(base + route, limits, resources), "HTTP rejection " + route);
                }
            }
            var resources = new ImageFetcher.Resources();
            resources.track(worker.submit(() -> {
                try { fetcher.download(base + "/stall", limits, resources); }
                catch (Exception expected) { }
                finally { stopped.countDown(); }
            }));
            try {
                check(stalled.await(5, TimeUnit.SECONDS), "Stalled body began");
                resources.close();
                check(stopped.await(5, TimeUnit.SECONDS), "Cancellation stops stalled HTTP body read");
            } finally { resources.close(); }
        } finally {
            release.countDown();
            fetcher.close();
            server.stop(0);
            worker.shutdownNow();
            handlers.shutdownNow();
        }
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing action, String label) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (Exception expected) { rejected = true; }
        check(rejected, label);
    }
    private static void check(boolean passed, String label) {
        assertions++;
        if (!passed) throw new AssertionError(label);
    }
}
