package net.jdr2021.preview.http;

/**
 * Result returned by {@link PreviewHttpServer#readAtMost(String, int)}.
 *
 * <p>Regular access returns a defensive copy. The preview pipeline may use
 * {@link #consumeBytes()} once to transfer ownership of a large result without
 * creating another full-size array.</p>
 */
public final class LimitedReadResult {

    private byte[] bytes;
    private final boolean truncated;
    private final long detectedSize;

    LimitedReadResult(byte[] bytes, boolean truncated, long detectedSize) {
        this.bytes = bytes;
        this.truncated = truncated;
        this.detectedSize = detectedSize;
    }

    public synchronized byte[] getBytes() {
        return bytes.clone();
    }

    /**
     * Transfers the internal byte array to the caller. Later reads return an
     * empty array; this is intended for the single-consumer preview pipeline.
     */
    public synchronized byte[] consumeBytes() {
        byte[] consumed = bytes;
        bytes = new byte[0];
        return consumed;
    }

    /**
     * Indicates that the remote object contains data beyond {@link #getBytes()}.
     */
    public boolean isTruncated() {
        return truncated;
    }

    /**
     * Returns the detected complete object size, or {@code -1} when the origin
     * did not expose it.
     */
    public long getDetectedSize() {
        return detectedSize;
    }
}
