package net.jdr2021.preview;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class OoxmlArchiveRendererTest {
    @Test
    public void rendersDocxMainText() throws Exception {
        Map<String, String> parts = new LinkedHashMap<String, String>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("word/document.xml",
                "<w:document xmlns:w=\"urn:w\"><w:body><w:p><w:r><w:t>" +
                        "第一段 &amp; escaped</w:t></w:r></w:p>" +
                        "<w:p><w:r><w:t>第二段</w:t></w:r></w:p></w:body></w:document>");
        byte[] docx = zip(parts);
        assertEquals(PreviewFormat.DOCX,
                FormatDetector.detect("file.docx", null, docx));
        assertEquals(PreviewFormat.TEXT,
                FormatDetector.detect("file.bin", null, docx));
        String html = new OoxmlPreviewRenderer().render(
                PreviewRequest.inMemory("sample.docx", null, docx)).bodyAsUtf8();
        assertTrue(html.contains("第一段 &amp; escaped"));
        assertTrue(html.contains("第二段"));
    }

    @Test
    public void rendersXlsxSharedStringsAndPptxSlides() throws Exception {
        Map<String, String> xlsxParts = new LinkedHashMap<String, String>();
        xlsxParts.put("xl/workbook.xml", "<workbook/>");
        xlsxParts.put("xl/sharedStrings.xml",
                "<sst xmlns=\"urn:x\"><si><t>姓名</t></si><si><t>张三</t></si></sst>");
        xlsxParts.put("xl/worksheets/sheet1.xml",
                "<worksheet xmlns=\"urn:x\"><sheetData><row r=\"1\">" +
                        "<c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>" +
                        "</row></sheetData></worksheet>");
        byte[] xlsx = zip(xlsxParts);
        String xlsxHtml = new OoxmlPreviewRenderer().render(
                PreviewRequest.inMemory("sample.xlsx", null, xlsx)).bodyAsUtf8();
        assertTrue(xlsxHtml.contains("<td title=\"A1\">姓名</td>"));
        assertTrue(xlsxHtml.contains("张三"));

        Map<String, String> pptxParts = new LinkedHashMap<String, String>();
        pptxParts.put("ppt/presentation.xml", "<p:presentation xmlns:p=\"urn:p\"/>");
        pptxParts.put("ppt/slides/slide1.xml",
                "<p:sld xmlns:p=\"urn:p\" xmlns:a=\"urn:a\"><a:t>季度总结</a:t>" +
                        "<a:t>增长 30%</a:t></p:sld>");
        String pptHtml = new OoxmlPreviewRenderer().render(
                PreviewRequest.inMemory("sample.pptx", null, zip(pptxParts))).bodyAsUtf8();
        assertTrue(pptHtml.contains("幻灯片 1"));
        assertTrue(pptHtml.contains("季度总结"));
        assertTrue(pptHtml.contains("增长 30%"));
    }

    @Test
    public void listsZipAndRejectsTraversalPath() throws Exception {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        entries.put("folder/readme.txt", "hello");
        entries.put("../outside.txt", "blocked");
        byte[] archive = zip(entries);
        String html = new ArchivePreviewRenderer().render(
                PreviewRequest.inMemory("sample.zip", null, archive)).bodyAsUtf8();
        assertTrue(html.contains("folder/readme.txt"));
        assertTrue(html.contains("因路径、大小或压缩比策略而隐藏"));
        assertTrue(!html.contains("../outside.txt"));

        ArchiveBrowser browser =
                ArchiveBrowser.open("sample.zip", PreviewFormat.ZIP, archive);
        assertEquals(1, browser.getEntries().size());
        assertEquals(1, browser.getRejectedCount());
        assertEquals("folder/readme.txt",
                browser.getEntries().get(0).getPath());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                browser.readEntry(browser.getEntries().get(0)));
        ArchiveBrowser jarBrowser =
                ArchiveBrowser.open("sample.jar", PreviewFormat.JAR, archive);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                jarBrowser.readEntry(jarBrowser.getEntries().get(0)));
    }

    @Test
    public void listsTarDirectoryWithoutExtraction() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes);
        byte[] content = "tar payload".getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry("docs/readme.txt");
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
        tar.finish();
        tar.close();

        byte[] archive = bytes.toByteArray();
        String html = new ArchivePreviewRenderer().render(
                PreviewRequest.inMemory("sample.tar", null, archive)).bodyAsUtf8();
        assertTrue(html.contains("docs/readme.txt"));
        assertTrue(html.contains("11 B"));
        ArchiveBrowser browser =
                ArchiveBrowser.open("sample.tar", PreviewFormat.TAR, archive);
        assertArrayEquals(content,
                browser.readEntry(browser.getEntries().get(0)));
    }

    @Test
    public void listsSevenZFromMemoryChannel() throws Exception {
        SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel();
        SevenZOutputFile output = new SevenZOutputFile(channel);
        byte[] content = "seven z payload".getBytes(StandardCharsets.UTF_8);
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName("documents/report.txt");
        entry.setSize(content.length);
        output.putArchiveEntry(entry);
        output.write(content);
        output.closeArchiveEntry();
        output.finish();
        output.close();
        byte[] archive = Arrays.copyOf(channel.array(), (int) channel.size());

        String html = new ArchivePreviewRenderer().render(
                PreviewRequest.inMemory("sample.7z", null, archive)).bodyAsUtf8();
        assertTrue(html.contains("documents/report.txt"));
        assertTrue(html.contains("15 B"));
        ArchiveBrowser browser =
                ArchiveBrowser.open("sample.7z", PreviewFormat.SEVEN_Z, archive);
        assertArrayEquals(content,
                browser.readEntry(browser.getEntries().get(0)));
    }

    @Test
    public void listsRar4DirectoryFromMemory() throws Exception {
        // Public Junrar test fixture rar4.rar: FILE1.TXT and FILE2.TXT.
        byte[] rar = Base64.getDecoder().decode(
                "UmFyIRoHAM+QcwAADQAAAAAAAAAJl3QggCkABwAAAAcAAAACun0Zem4DYz0d"
                        + "MAkAIAAAAEZJTEUxLlRYVGZpbGUxDQo8d3QggCkABwAAAAcAAAAC48Nf"
                        + "eHEDYz0dMAkAIAAAAEZJTEUyLlRYVGZpbGUyDQrEPXsAQAcA");

        String html = new ArchivePreviewRenderer().render(
                PreviewRequest.inMemory("sample.rar", null, rar)).bodyAsUtf8();

        assertTrue(html.contains("FILE1.TXT"));
        assertTrue(html.contains("FILE2.TXT"));
        ArchiveBrowser browser =
                ArchiveBrowser.open("sample.rar", PreviewFormat.RAR, rar);
        assertEquals(2, browser.getEntries().size());
        assertArrayEquals("file1\r\n".getBytes(StandardCharsets.UTF_8),
                browser.readEntry(browser.getEntries().get(0)));
    }

    @Test
    public void listsGzipStreamMetadata() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        gzip.write("gzip payload".getBytes(StandardCharsets.UTF_8));
        gzip.finish();
        gzip.close();

        byte[] archive = bytes.toByteArray();
        String html = new ArchivePreviewRenderer().render(
                PreviewRequest.inMemory("payload.txt.gz", null, archive))
                .bodyAsUtf8();

        assertTrue(html.contains("payload.txt"));
        assertTrue(html.contains("12 B"));
        ArchiveBrowser browser =
                ArchiveBrowser.open("payload.txt.gz", PreviewFormat.GZIP, archive);
        assertArrayEquals("gzip payload".getBytes(StandardCharsets.UTF_8),
                browser.readEntry(browser.getEntries().get(0)));
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bytes);
        for (Map.Entry<String, String> item : entries.entrySet()) {
            zip.putNextEntry(new ZipEntry(item.getKey()));
            zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        zip.finish();
        zip.close();
        return bytes.toByteArray();
    }
}
