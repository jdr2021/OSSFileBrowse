package net.jdr2021.preview;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RenderingPolicyTest {
    @Test
    public void textRendererEscapesUntrustedMarkup() {
        PreviewResult result = new TextPreviewRenderer().render(PreviewRequest.inMemory(
                "payload.txt", "text/plain",
                "<script>alert('x') & more</script>".getBytes(StandardCharsets.UTF_8)));
        String html = result.bodyAsUtf8();
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&amp; more"));
        assertFalse(html.contains("<script>alert"));
        assertTrue(html.contains("<meta charset=\"UTF-8\">"));
    }

    @Test
    public void unknownAlwaysUsesPlainTextWithoutItsOwnSizeThreshold() {
        UnknownPreviewRenderer renderer = new UnknownPreviewRenderer();
        PreviewResult text = renderer.render(PreviewRequest.inMemory(
                "note.odd", null, "hello 世界".getBytes(StandardCharsets.UTF_8)));
        assertTrue(text.bodyAsUtf8().contains("hello 世界"));
        assertFalse(text.isDownloadSuggested());

        PreviewResult binary = renderer.render(PreviewRequest.inMemory(
                "data.odd", null, new byte[]{0, 1, (byte) 0xFF}));
        assertTrue(binary.bodyAsUtf8().contains("纯文本预览"));
        assertTrue(binary.bodyAsUtf8().contains("ISO-8859-1"));
        assertFalse(binary.bodyAsUtf8().contains("十六进制"));

        PreviewRequest large = new PreviewRequest("large.odd", null,
                6L * 1024 * 1024, new byte[]{1}, null,
                PreviewFormat.UNKNOWN);
        PreviewResult message = renderer.render(large);
        assertFalse(message.isDownloadSuggested());
        assertTrue(message.bodyAsUtf8().contains("纯文本预览"));
    }

    @Test
    public void previewServiceRoutesUnknownAndMissingSuffixesToText()
            throws Exception {
        PreviewService service = new PreviewService();
        PreviewResult unknownSuffix = service.render(
                PreviewRequest.inMemory("payload.custom", null,
                        new byte[]{0, 1, (byte) 0xFF}));
        PreviewResult missingSuffix = service.render(
                PreviewRequest.inMemory("README", "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));

        assertTrue(unknownSuffix.bodyAsUtf8().contains("类型：text"));
        assertTrue(missingSuffix.bodyAsUtf8().contains("类型：text"));
        assertFalse(missingSuffix.bodyAsUtf8().contains("PDF ·"));
    }

    @Test
    public void htmlResourceIsSandboxed() {
        PreviewRequest request = new PreviewRequest("page.html", "text/html", 20,
                "<script>top.location='x'</script>".getBytes(StandardCharsets.UTF_8),
                "http://127.0.0.1:18080/proxy/TOKEN/object", PreviewFormat.HTML);
        String html = new ProxyAssetPreviewRenderer().render(request).bodyAsUtf8();
        assertTrue(html.contains("sandbox=\"\""));
        assertTrue(html.contains("referrerpolicy=\"no-referrer\""));
        assertTrue(html.contains("http://127.0.0.1:*"));
        assertFalse(html.contains("top.location"));
    }

    @Test
    public void mediaHtmlNeverCreatesWebKitVideoOrAudioElements() {
        PreviewRequest request = new PreviewRequest("clip.mp4", "video/mp4", 100,
                new byte[0], "http://127.0.0.1:18080/proxy/TOKEN/object",
                PreviewFormat.VIDEO);
        String html = new ProxyAssetPreviewRenderer().render(request).bodyAsUtf8();

        assertTrue(html.contains("MediaView"));
        assertFalse(html.contains("<video"));
        assertFalse(html.contains("<audio"));
    }

    @Test
    public void pdfRendererProvidesMemoryPageApi() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PDDocument created = new PDDocument();
        created.addPage(new PDPage());
        created.save(bytes);
        created.close();

        PdfPreviewRenderer renderer = new PdfPreviewRenderer();
        assertTrue(renderer.pageCount(bytes.toByteArray()) == 1);
        byte[] png = renderer.renderPage(bytes.toByteArray(), 0, 72);
        assertTrue(png.length > 8);
        assertTrue(png[0] == (byte) 0x89 && png[1] == 'P' && png[2] == 'N');
    }
}
