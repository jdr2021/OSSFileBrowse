package net.jdr2021.preview;

import java.io.IOException;

/**
 * Archive summary page. The same {@link ArchiveBrowser} instance can also be
 * attached to the JavaFX tree, so the HTML summary and selectable entries use
 * exactly the same path and expansion policies.
 */
public final class ArchivePreviewRenderer extends HtmlPreviewRenderer {
    public static final int MAX_ENTRIES = ArchiveBrowser.MAX_ENTRIES;
    public static final long MAX_EXPANDED_BYTES =
            ArchiveBrowser.MAX_EXPANDED_BYTES;
    public static final long MAX_ENTRY_BYTES = ArchiveBrowser.MAX_ENTRY_BYTES;
    public static final int MAX_COMPRESSION_RATIO =
            ArchiveBrowser.MAX_COMPRESSION_RATIO;

    @Override
    public boolean supports(PreviewFormat format) {
        return format == PreviewFormat.ZIP || format == PreviewFormat.JAR
                || format == PreviewFormat.TAR || format == PreviewFormat.SEVEN_Z
                || format == PreviewFormat.RAR || format == PreviewFormat.GZIP;
    }

    @Override
    public PreviewResult render(PreviewRequest request) throws IOException {
        ArchiveBrowser browser = ArchiveBrowser.open(
                request.getFileName(), request.getFormat(), request.getData());
        return render(request, browser);
    }

    public PreviewResult render(PreviewRequest request, ArchiveBrowser browser) {
        return page(request.getFileName(), listingHtml(request, browser));
    }

    private static String listingHtml(PreviewRequest request,
                                      ArchiveBrowser browser) {
        StringBuilder html = new StringBuilder();
        html.append("<h1>").append(escape(request.getFileName())).append("</h1>")
                .append("<div class=\"muted\">").append(request.getFormat())
                .append(" · ").append(browser.getEntries().size())
                .append(" 个条目 · 展开大小 ")
                .append(humanSize(browser.getTotalExpandedBytes()))
                .append("</div>")
                .append("<div class=\"card\">压缩包目录已展开到左侧文件树；"
                        + "点击其中的文件即可预览。</div>");
        if (browser.getRejectedCount() > 0) {
            html.append("<div class=\"card danger\">有 ")
                    .append(browser.getRejectedCount())
                    .append(" 个条目因路径、大小或压缩比策略而隐藏。</div>");
        }
        html.append("<table><thead><tr><th>路径</th><th>类型</th><th>大小</th>")
                .append("<th>压缩后</th></tr></thead><tbody>");
        for (ArchiveBrowser.Entry entry : browser.getEntries()) {
            html.append("<tr><td>").append(escape(entry.getPath()))
                    .append("</td><td>")
                    .append(escape(entry.getType())).append("</td><td>")
                    .append(humanSize(entry.getSize())).append("</td><td>")
                    .append(humanSize(entry.getCompressedSize()))
                    .append("</td></tr>");
        }
        return html.append("</tbody></table>").toString();
    }
}
