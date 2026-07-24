package net.jdr2021.preview;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class FormatDetectorTest {
    @Test
    public void detectsExtensionMimeAndMagic() {
        assertEquals(PreviewFormat.DOCX,
                FormatDetector.detect("report.docx", null, new byte[0]));
        assertEquals(PreviewFormat.DOCX,
                FormatDetector.detect("report.docx", null, new byte[]{'P', 'K', 3, 4}));
        assertEquals(PreviewFormat.XLSX,
                FormatDetector.detect("object.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        new byte[0]));
        assertEquals(PreviewFormat.PDF,
                FormatDetector.detect("wrong.txt", "text/plain",
                        "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(PreviewFormat.RAR,
                FormatDetector.detect("archive.rar", null,
                        new byte[]{'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00}));
        assertEquals(PreviewFormat.SEVEN_Z,
                FormatDetector.detect("archive.7z", null,
                        new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}));
        assertEquals(PreviewFormat.VIDEO,
                FormatDetector.detect("clip.mp4", null,
                        new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}));
        assertEquals(PreviewFormat.AUDIO,
                FormatDetector.detect("track.m4a", "audio/mp4",
                        new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}));
        assertEquals(PreviewFormat.XLS,
                FormatDetector.detect("sheet.xls", "application/vnd.ms-excel",
                        new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}));
        assertEquals(PreviewFormat.HTML,
                FormatDetector.detect("page.html", null,
                        "<!doctype html><title>x</title>".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void recognizesRequestedExtensionFamilies() {
        assertEquals(PreviewFormat.DOC, FormatDetector.detect("a.doc", null, null));
        assertEquals(PreviewFormat.XLS, FormatDetector.detect("a.xls", null, null));
        assertEquals(PreviewFormat.PPT, FormatDetector.detect("a.ppt", null, null));
        assertEquals(PreviewFormat.JSON, FormatDetector.detect("a.json", null, null));
        assertEquals(PreviewFormat.XML, FormatDetector.detect("a.xml", null, null));
        assertEquals(PreviewFormat.MARKDOWN, FormatDetector.detect("a.md", null, null));
        assertEquals(PreviewFormat.CODE, FormatDetector.detect("a.java", null, null));
        assertEquals(PreviewFormat.IMAGE, FormatDetector.detect("a.jpg", null, null));
        assertEquals(PreviewFormat.AUDIO, FormatDetector.detect("a.mp3", null, null));
        assertEquals(PreviewFormat.ZIP, FormatDetector.detect("a.zip", null, null));
        assertEquals(PreviewFormat.JAR, FormatDetector.detect("a.jar", null, null));
        assertEquals(PreviewFormat.TAR, FormatDetector.detect("a.tar", null, null));
        assertEquals(PreviewFormat.GZIP, FormatDetector.detect("a.gz", null, null));
        assertEquals(PreviewFormat.TEXT, FormatDetector.detect("a.csv", null, null));
        assertEquals(PreviewFormat.TEXT,
                FormatDetector.detect("a.custom", null, new byte[]{0, 1, 2, 3}));
        assertEquals(PreviewFormat.TEXT,
                FormatDetector.detect("a.custom", null,
                        "unknown suffix uses plain text"
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(PreviewFormat.TEXT,
                FormatDetector.detect("README", "application/pdf",
                        "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(PreviewFormat.TEXT,
                FormatDetector.detect("README", null,
                        new byte[]{'P', 'K', 3, 4}));
    }
}
