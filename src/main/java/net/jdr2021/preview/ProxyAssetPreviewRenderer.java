package net.jdr2021.preview;

import java.util.Base64;

/**
 * Browser-native renderer for images, sandboxed HTML and seekable media.
 * LocalGateway should supply a tokenized loopback proxy URL which implements
 * HEAD/Range. In-memory data URLs are used by tests and small standalone calls.
 */
public final class ProxyAssetPreviewRenderer extends HtmlPreviewRenderer {
    @Override
    public boolean supports(PreviewFormat format) {
        return format == PreviewFormat.IMAGE || format == PreviewFormat.HTML
                || format == PreviewFormat.VIDEO || format == PreviewFormat.AUDIO;
    }

    @Override
    public PreviewResult render(PreviewRequest request) {
        String url = request.getProxyUrl();
        if (url == null || url.trim().isEmpty()) {
            url = "data:" + safeMime(request.getMimeType()) + ";base64," +
                    Base64.getEncoder().encodeToString(request.getData());
        }
        String safeUrl = attribute(url);
        String element;
        switch (request.getFormat()) {
            case IMAGE:
                element = "<div class=\"card\"><img alt=\"" +
                        attribute(request.getFileName()) + "\" src=\"" + safeUrl + "\"></div>";
                break;
            case VIDEO:
            case AUDIO:
                // JavaFX 8 WebKit can terminate the process while initializing
                // some H.264 MP4 files. The application controller routes media
                // to JavaFX MediaView; standalone HTML stays metadata-only.
                element = "<div class=\"card\"><p>媒体资源已识别。</p>"
                        + "<p class=\"muted\">应用内预览使用 JavaFX MediaView 和本机代理，"
                        + "此 HTML 页面不创建 WebKit 媒体元素。</p></div>";
                break;
            case HTML:
                // Empty sandbox blocks script, forms, top navigation and same-origin
                // privileges while retaining a faithful static browser rendering.
                element = "<iframe sandbox=\"\" referrerpolicy=\"no-referrer\" src=\"" +
                        safeUrl + "\" title=\"" + attribute(request.getFileName()) + "\"></iframe>";
                break;
            default:
                throw new IllegalArgumentException("Unsupported direct format");
        }
        return page(request.getFileName(),
                "<h1>" + escape(request.getFileName()) + "</h1>" + element);
    }

    private static String safeMime(String mime) {
        if (mime == null || !mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) {
            return "application/octet-stream";
        }
        return mime;
    }
}
