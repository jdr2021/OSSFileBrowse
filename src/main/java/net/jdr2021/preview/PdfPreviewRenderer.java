package net.jdr2021.preview;

import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * PDFBox-backed, memory-only paged PDF renderer. LocalGateway can expose
 * renderPage as /preview/{session}/page/{n}.png; the generic SPI embeds page 1
 * to provide a useful standalone HTML result.
 */
public final class PdfPreviewRenderer extends HtmlPreviewRenderer {
    private static final long MAX_PDF_BYTES = 128L * 1024 * 1024;
    private static final float DEFAULT_DPI = 110f;

    @Override
    public boolean supports(PreviewFormat format) {
        return format == PreviewFormat.PDF;
    }

    @Override
    public PreviewResult render(PreviewRequest request) throws IOException {
        ensureSize(request.getData());
        int pages = pageCount(request.getData());
        if (pages == 0) {
            return page(request.getFileName(), "<div class=\"card danger\">PDF 没有页面。</div>");
        }
        byte[] png = renderPage(request.getData(), 0, DEFAULT_DPI);
        String image = Base64.getEncoder().encodeToString(png);
        String body = "<h1>" + escape(request.getFileName()) + "</h1>" +
                "<div class=\"muted\">PDF · 共 " + pages +
                " 页 · 当前第 1 页；LocalGateway 可通过分页 API 按需渲染其余页面。</div>" +
                "<div class=\"card\"><img alt=\"PDF 第 1 页\" src=\"data:image/png;base64," +
                image + "\"></div>";
        return page(request.getFileName(), body);
    }

    public int pageCount(byte[] pdf) throws IOException {
        ensureSize(pdf);
        PDDocument document = load(pdf);
        try {
            return document.getNumberOfPages();
        } finally {
            document.close();
        }
    }

    public byte[] renderPage(byte[] pdf, int zeroBasedPage, float dpi) throws IOException {
        ensureSize(pdf);
        if (dpi < 72f || dpi > 200f) {
            throw new IllegalArgumentException("DPI must be between 72 and 200");
        }
        PDDocument document = load(pdf);
        try {
            int count = document.getNumberOfPages();
            if (zeroBasedPage < 0 || zeroBasedPage >= count) {
                throw new IndexOutOfBoundsException("PDF page index: " + zeroBasedPage);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(
                    zeroBasedPage, dpi, ImageType.RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG encoder is not available");
            }
            image.flush();
            return output.toByteArray();
        } finally {
            document.close();
        }
    }

    private static PDDocument load(byte[] pdf) throws IOException {
        return PDDocument.load(new ByteArrayInputStream(pdf),
                MemoryUsageSetting.setupMainMemoryOnly());
    }

    private static void ensureSize(byte[] pdf) throws IOException {
        if (pdf == null || pdf.length == 0) throw new IOException("PDF data is empty");
        if (pdf.length > MAX_PDF_BYTES) throw new IOException("PDF exceeds 128 MiB memory limit");
    }
}
