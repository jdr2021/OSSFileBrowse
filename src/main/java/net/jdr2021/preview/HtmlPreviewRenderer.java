package net.jdr2021.preview;

/** Shared UTF-8 HTML envelope and context-aware escaping helpers. */
public abstract class HtmlPreviewRenderer implements PreviewRenderer {
    private static final String STYLE =
            "html{color-scheme:light}body{margin:0;padding:18px;font:14px/1.55 " +
            "-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;" +
            "color:#243040;background:#fff}h1{font-size:18px;margin:0 0 14px}" +
            "h2{font-size:16px;margin:18px 0 8px}.card{border:1px solid #dfe5ec;" +
            "border-radius:7px;padding:14px;margin:10px 0}.muted{color:#697586}" +
            "pre{white-space:pre-wrap;word-break:break-word;background:#f6f8fa;" +
            "border:1px solid #e5e9ef;border-radius:5px;padding:12px;tab-size:4}" +
            "table{border-collapse:collapse;min-width:50%;max-width:100%}" +
            "th,td{border:1px solid #d8dee6;padding:5px 8px;text-align:left;" +
            "vertical-align:top}th{background:#f3f5f8}.danger{color:#b42318}" +
            "img,video{max-width:100%;max-height:calc(100vh - 56px)}" +
            "iframe{width:100%;height:calc(100vh - 40px);border:0}";

    protected final PreviewResult page(String title, String body) {
        return PreviewResult.html(document(title, body));
    }

    protected final PreviewResult page(int status, String title, String body,
                                       boolean downloadSuggested) {
        return PreviewResult.html(status, document(title, body), downloadSuggested);
    }

    public static String document(String title, String body) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
                "default-src 'none'; img-src 'self' data: blob: http://127.0.0.1:*; " +
                "media-src 'self' blob: http://127.0.0.1:*; style-src 'unsafe-inline'; " +
                "script-src 'unsafe-inline'; frame-src 'self' data: blob: " +
                "http://127.0.0.1:*;\">" +
                "<title>" + escape(title) + "</title><style>" + STYLE +
                "</style></head><body>" + body + "</body></html>";
    }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': escaped.append("&amp;"); break;
                case '<': escaped.append("&lt;"); break;
                case '>': escaped.append("&gt;"); break;
                case '"': escaped.append("&quot;"); break;
                case '\'': escaped.append("&#39;"); break;
                default: escaped.append(c);
            }
        }
        return escaped.toString();
    }

    public static String attribute(String value) {
        return escape(value == null ? "" : value)
                .replace("`", "&#96;")
                .replace("\r", "&#13;")
                .replace("\n", "&#10;");
    }

    protected static String humanSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MiB", bytes / 1048576.0);
        return String.format("%.1f GiB", bytes / 1073741824.0);
    }
}
