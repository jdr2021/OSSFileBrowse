package net.jdr2021.preview;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Text/code renderer with BOM, UTF-8, UTF-16 and GB18030 detection. */
public final class TextPreviewRenderer extends HtmlPreviewRenderer {
    @Override
    public boolean supports(PreviewFormat format) {
        return format == PreviewFormat.TEXT || format == PreviewFormat.JSON
                || format == PreviewFormat.XML || format == PreviewFormat.MARKDOWN
                || format == PreviewFormat.CODE;
    }

    @Override
    public PreviewResult render(PreviewRequest request) {
        DecodedText decoded = decode(request.getData());
        String language = request.getFormat().name().toLowerCase();
        String body = "<h1>" + escape(request.getFileName()) + "</h1>" +
                "<div class=\"muted\">编码：" + escape(decoded.charsetName) +
                " · " + humanSize(request.getDeclaredSize()) +
                " · 类型：" + escape(language) + "</div>" +
                "<pre><code class=\"language-" + attribute(language) + "\">" +
                escape(decoded.text) + "</code></pre>";
        return page(request.getFileName(), body);
    }

    public static DecodedText decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new DecodedText("", "UTF-8");
        }
        if (hasPrefix(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
            return new DecodedText(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8),
                    "UTF-8 BOM");
        }
        if (hasPrefix(bytes, (byte) 0xFF, (byte) 0xFE)) {
            return new DecodedText(new String(bytes, 2, bytes.length - 2,
                    StandardCharsets.UTF_16LE), "UTF-16LE");
        }
        if (hasPrefix(bytes, (byte) 0xFE, (byte) 0xFF)) {
            return new DecodedText(new String(bytes, 2, bytes.length - 2,
                    StandardCharsets.UTF_16BE), "UTF-16BE");
        }
        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new DecodedText(utf8, "UTF-8");
        }
        Charset gb18030 = Charset.forName("GB18030");
        String chinese = strictDecode(bytes, gb18030);
        if (chinese != null) {
            return new DecodedText(chinese, "GB18030");
        }
        return new DecodedText(new String(bytes, StandardCharsets.ISO_8859_1),
                "ISO-8859-1");
    }

    private static String strictDecode(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException ignored) {
            return null;
        }
    }

    private static boolean hasPrefix(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }

    public static final class DecodedText {
        private final String text;
        private final String charsetName;

        private DecodedText(String text, String charsetName) {
            this.text = text;
            this.charsetName = charsetName;
        }

        public String getText() { return text; }
        public String getCharsetName() { return charsetName; }
    }
}
