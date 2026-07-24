package net.jdr2021.preview;

import net.jdr2021.preview.http.PreviewHttpServer;
import net.jdr2021.preview.http.RegisteredLocalContent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PagedPdfPreviewTest {
    @Test
    public void servesEveryPdfPageThroughTokenizedLocalGateway() throws Exception {
        byte[] pdf = twoPagePdf();
        PagedPdfContentProvider provider = new PagedPdfContentProvider(pdf);
        PreviewHttpServer gateway = PreviewHttpServer.start();
        try {
            RegisteredLocalContent local = gateway.registerLocal(provider);
            PreviewResult page = PagedPdfPreviewPage.create(
                    "sample.pdf", provider.getPageCount(), local);

            assertEquals(2, provider.getPageCount());
            assertTrue(page.bodyAsUtf8().contains("第 1 / 2 页"));
            assertTrue(page.bodyAsUtf8().contains("onclick=\"move(1)\""));

            HttpURLConnection second = (HttpURLConnection)
                    local.resolve("page/1.png").toURL().openConnection();
            assertEquals(200, second.getResponseCode());
            assertEquals("image/png", second.getContentType());
            try (InputStream input = second.getInputStream()) {
                assertEquals(0x89, input.read());
                assertEquals('P', input.read());
                assertEquals('N', input.read());
                assertEquals('G', input.read());
            } finally {
                second.disconnect();
            }

            HttpURLConnection missing = (HttpURLConnection)
                    local.resolve("page/2.png").toURL().openConnection();
            assertEquals(404, missing.getResponseCode());
            missing.disconnect();
        } finally {
            gateway.close();
        }
    }

    private static byte[] twoPagePdf() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PDDocument document = new PDDocument();
        try {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(output);
        } finally {
            document.close();
        }
        return output.toByteArray();
    }
}
