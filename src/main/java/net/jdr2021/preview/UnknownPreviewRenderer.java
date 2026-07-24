package net.jdr2021.preview;

/** Last-resort renderer: unknown content is always shown as plain text. */
public final class UnknownPreviewRenderer extends HtmlPreviewRenderer {
    @Override
    public boolean supports(PreviewFormat format) {
        return format == PreviewFormat.UNKNOWN;
    }

    @Override
    public PreviewResult render(PreviewRequest request) {
        TextPreviewRenderer.DecodedText decoded =
                TextPreviewRenderer.decode(request.getData());
        String body = "<h1>" + escape(request.getFileName()) + "</h1>"
                + "<div class=\"muted\">未知格式 · 纯文本预览 · 编码："
                + escape(decoded.getCharsetName()) + "</div><pre>"
                + escape(displayableText(decoded.getText())) + "</pre>";
        return page(request.getFileName(), body);
    }

    private static String displayableText(String value) {
        StringBuilder text = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current < 0x20
                    && current != '\t'
                    && current != '\r'
                    && current != '\n')
                    || current == 0x7F) {
                text.append('\uFFFD');
            } else {
                text.append(current);
            }
        }
        return text.toString();
    }
}
