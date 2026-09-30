package space.essem.image2map.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class ImageSafety {
    private ImageSafety() { }

    public record Limits(long maxBytes, List<String> formats) { }
    public record Info(String format, int width, int height) { }
    public record Decoded(BufferedImage image, Info info) { }

    public static String cleanPath(String input) {
        String value = input == null ? "" : input.strip();
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).strip();
        }
        return value;
    }

    public static boolean isHttp(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Server paths must stay below the game directory, including symlink targets. */
    public static Path serverPath(Path gameDir, String input) throws IOException {
        try {
            // Reject Windows absolute/UNC/drive paths on Unix servers too.
            if (input.isBlank() || input.startsWith("/") || input.startsWith("\\") || input.contains(":")) {
                throw new IOException("Server file paths must be relative to the game directory");
            }
            for (String part : input.split("[/\\\\]")) {
                if (part.equals("..")) throw new IOException("Parent traversal is not allowed for server files");
            }
            Path relative = Path.of(input);
            Path root = gameDir.toRealPath();
            Path path = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !path.startsWith(root)) throw new IOException("Unsafe server file path");
            Path current = root;
            for (Path part : root.relativize(path)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current)) throw new IOException("Server file symlinks are not allowed");
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().startsWith(root)) {
                throw new IOException("Server file path escapes the game directory");
            }
            return path;
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid file path", exception);
        }
    }

    public static String normalizeFormat(String format) {
        String lower = format.toLowerCase(Locale.ROOT);
        return lower.equals("jpg") ? "jpeg" : lower;
    }

    public static void verifyDeclaration(Info declared, Info actual) throws IOException {
        if (!normalizeFormat(declared.format()).equals(normalizeFormat(actual.format()))
                || declared.width() != actual.width() || declared.height() != actual.height()) {
            throw new IOException("Actual image content does not match the declared metadata");
        }
    }

    public static void validateInfo(Info info, long size, Limits limits) throws IOException {
        if (size < 1 || size > limits.maxBytes()) throw new IOException("Image file exceeds the allowed byte size");
        if (info.width() < 1 || info.height() < 1) {
            throw new IOException("Invalid source image dimensions");
        }
        if (limits.formats().stream().noneMatch(x -> normalizeFormat(x).equals(normalizeFormat(info.format())))) {
            throw new IOException("Image format is not allowed: " + info.format());
        }
    }

    public static byte[] readBounded(InputStream stream, long maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Image operation cancelled");
            if ((long) output.size() + count > maxBytes) throw new IOException("Image file exceeds the allowed byte size");
            output.write(buffer, 0, count);
        }
        if (output.size() == 0) throw new IOException("Image file is empty");
        return output.toByteArray();
    }

    public static Info inspect(byte[] data, Limits limits) throws IOException {
        return read(data, limits, false).info();
    }

    public static Decoded decode(byte[] data, Limits limits) throws IOException {
        return read(data, limits, true);
    }

    private static Decoded read(byte[] data, Limits limits, boolean decode) throws IOException {
        if (data.length < 1 || data.length > limits.maxBytes()) throw new IOException("Invalid image file size");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("File content is not a supported image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                Info info = new Info(normalizeFormat(reader.getFormatName()), reader.getWidth(0), reader.getHeight(0));
                validateInfo(info, data.length, limits); // Before allocating decoded pixels.
                BufferedImage image = decode ? reader.read(0) : null;
                if (decode && (image == null || image.getWidth() != info.width() || image.getHeight() != info.height())) {
                    throw new IOException("Image dimensions changed while decoding");
                }
                return new Decoded(image, info);
            } finally {
                reader.dispose();
            }
        }
    }
}
