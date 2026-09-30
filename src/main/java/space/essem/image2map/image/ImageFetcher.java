package space.essem.image2map.image;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Future;

/** Shared bounded loading for HTTP and safe server files. */
public final class ImageFetcher {
    private final HttpClient client;
    private final int timeout;

    public ImageFetcher(int timeout) {
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Closing this handle interrupts downloads even while a body read is stalled. */
    public static final class Resources implements AutoCloseable {
        private InputStream stream;
        private Future<?> future;
        private boolean closed;

        public synchronized void track(Future<?> value) {
            if (closed) value.cancel(true);
            else future = value;
        }

        public synchronized void track(InputStream value) throws IOException {
            if (closed) {
                value.close();
                throw new IOException("Image task cancelled");
            }
            stream = value;
        }

        @Override public synchronized void close() {
            closed = true;
            if (future != null) future.cancel(true);
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
                stream = null;
            }
        }
    }

    public byte[] download(String url, ImageSafety.Limits limits, Resources resources) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(timeout))
                .header("User-Agent", "Image2Map mod").build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            resources.track(stream);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Image download failed: HTTP " + response.statusCode());
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length > limits.maxBytes()) throw new IOException("Image download exceeds the allowed byte size");
            return ImageSafety.readBounded(stream, limits.maxBytes());
        }
    }

    public static byte[] file(Path path, ImageSafety.Limits limits, Resources resources) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Only regular image files are allowed");
        if (Files.size(path) > limits.maxBytes()) throw new IOException("Image file exceeds the allowed byte size");
        try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            resources.track(stream);
            return ImageSafety.readBounded(stream, limits.maxBytes());
        }
    }

    public void close() { client.shutdownNow(); }
}
