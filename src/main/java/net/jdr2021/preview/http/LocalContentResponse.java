package net.jdr2021.preview.http;

/**
 * Immutable body returned by a {@link LocalContentProvider}.
 */
public final class LocalContentResponse {
    private final int status;
    private final String contentType;
    private final byte[] body;

    public LocalContentResponse(int status, String contentType, byte[] body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("invalid HTTP status");
        }
        this.status = status;
        this.contentType = contentType == null
                ? "application/octet-stream" : contentType;
        this.body = body == null ? new byte[0] : body.clone();
    }

    public static LocalContentResponse ok(String contentType, byte[] body) {
        return new LocalContentResponse(200, contentType, body);
    }

    public static LocalContentResponse notFound() {
        return new LocalContentResponse(404, "text/plain; charset=UTF-8",
                "Not found".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public int getStatus() {
        return status;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getBody() {
        return body.clone();
    }

    byte[] bodyForServer() {
        return body;
    }
}
