package net.jdr2021.preview;

import java.nio.charset.StandardCharsets;

/** Result returned to LocalGateway without any filesystem materialization. */
public final class PreviewResult {
    private final int status;
    private final String contentType;
    private final byte[] body;
    private final boolean downloadSuggested;

    public PreviewResult(int status, String contentType, byte[] body, boolean downloadSuggested) {
        this.status = status;
        this.contentType = contentType;
        this.body = body == null ? new byte[0] : body;
        this.downloadSuggested = downloadSuggested;
    }

    public static PreviewResult html(String html) {
        return html(200, html, false);
    }

    public static PreviewResult html(int status, String html, boolean downloadSuggested) {
        return new PreviewResult(status, "text/html; charset=UTF-8",
                html.getBytes(StandardCharsets.UTF_8), downloadSuggested);
    }

    public int getStatus() { return status; }
    public String getContentType() { return contentType; }
    public byte[] getBody() { return body; }
    public boolean isDownloadSuggested() { return downloadSuggested; }
    public String bodyAsUtf8() { return new String(body, StandardCharsets.UTF_8); }
}
