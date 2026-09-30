package space.essem.image2map.upload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Bounded, ordered reassembly. Metadata never allocates the declared file size. */
public final class UploadBuffer {
    public static final int CHUNK_SIZE = 16 * 1024;
    private final long expectedSize;
    private final long maxSize;
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private int sequence;

    public UploadBuffer(long expectedSize, long maxSize) throws IOException {
        if (expectedSize < 1 || expectedSize > maxSize) throw new IOException("Invalid declared image size");
        this.expectedSize = expectedSize;
        this.maxSize = maxSize;
    }

    public void append(int index, byte[] bytes) throws IOException {
        if (index != sequence || bytes.length < 1 || bytes.length > CHUNK_SIZE) {
            throw new IOException("Invalid or out-of-order image chunk");
        }
        long size = (long) data.size() + bytes.length;
        if (size > maxSize || size > expectedSize) throw new IOException("Uploaded image exceeds its declared size");
        data.writeBytes(bytes);
        sequence++;
    }

    public byte[] complete() throws IOException {
        if (data.size() != expectedSize) throw new IOException("Uploaded image size does not match its declaration");
        return data.toByteArray();
    }
}
