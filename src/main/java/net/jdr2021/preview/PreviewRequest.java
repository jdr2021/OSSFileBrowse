package net.jdr2021.preview;

/**
 * Immutable rendering input. Data is kept in memory and intentionally exposed
 * by reference to avoid a second copy of large objects; callers must not mutate
 * it while rendering. The proxy URL is supplied by LocalGateway.
 */
public final class PreviewRequest {
    private final String fileName;
    private final String mimeType;
    private final long declaredSize;
    private final byte[] data;
    private final String proxyUrl;
    private final PreviewFormat format;

    public PreviewRequest(String fileName, String mimeType, long declaredSize,
                          byte[] data, String proxyUrl, PreviewFormat format) {
        this.fileName = fileName == null ? "" : fileName;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.data = data == null ? new byte[0] : data;
        this.declaredSize = declaredSize >= 0 ? declaredSize : this.data.length;
        this.proxyUrl = proxyUrl;
        this.format = format == null
                ? FormatDetector.detect(fileName, mimeType, this.data) : format;
    }

    public static PreviewRequest inMemory(String fileName, String mimeType, byte[] data) {
        return new PreviewRequest(fileName, mimeType, data == null ? 0 : data.length,
                data, null, null);
    }

    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public long getDeclaredSize() { return declaredSize; }
    public byte[] getData() { return data; }
    public String getProxyUrl() { return proxyUrl; }
    public PreviewFormat getFormat() { return format; }
}
