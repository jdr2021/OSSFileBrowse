package net.jdr2021.search;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SearchReportWriterTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void writesThreeColumnFilterableHtmlIntoWorkingDirectory()
            throws Exception {
        SearchMatch direct = new SearchMatch("Basic", "Domain",
                "api.example.com", "https://bucket.test/app.js",
                "https://bucket.test/app.js");
        SearchMatch archived = new SearchMatch("Sensitive", "Token",
                "secret-123",
                "https://bucket.test/a.zip!/inner/config.txt",
                "https://bucket.test/a.zip");
        SearchReport report = new SearchReport("api.example.com", 2,
                2, 1, 0, Arrays.asList(direct, archived),
                Collections.<String>emptyList());

        Path output = SearchReportWriter.write(
                report, temporary.getRoot().toPath(),
                "bucket.test");
        String html = new String(Files.readAllBytes(output),
                StandardCharsets.UTF_8);

        assertEquals("bucket.test_leak_info.html",
                output.getFileName().toString());
        assertTrue(html.contains("正则规则名称"));
        assertTrue(html.contains("采集到的样例数据"));
        assertTrue(html.contains("所属文件链接"));
        assertTrue(html.contains("id=\"filterButton\""));
        assertTrue(html.contains("id=\"sourceFilter\""));
        assertTrue(html.contains("data-source="));
        assertTrue(html.contains("a.zip!/inner/config.txt"));
    }

    @Test
    public void keepsIpAddressAsReportFileName() {
        assertEquals("192.168.1.10_leak_info.html",
                SearchReportWriter.reportFileName("192.168.1.10"));
        assertEquals("bucket_leak_info.html",
                SearchReportWriter.reportFileName(""));
    }
}
