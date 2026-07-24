package net.jdr2021.search;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.jdr2021.preview.http.PreviewHttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BucketSearchServiceTest {
    @Test
    public void scansRemoteTextAndArchiveEntries() throws Exception {
        byte[] text = "endpoint=domain.example.org"
                .getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip("inner/config.txt",
                "token=ARCHIVE_SECRET_123");
        HttpServer origin = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/app.js",
                exchange -> serve(exchange, text, "text/javascript"));
        origin.createContext("/bundle.zip",
                exchange -> serve(exchange, archive, "application/zip"));
        origin.start();

        PreviewHttpServer gateway = PreviewHttpServer.start();
        try {
            String base = "http://127.0.0.1:"
                    + origin.getAddress().getPort();
            List<SearchTarget> targets = Arrays.asList(
                    new SearchTarget("app.js", base + "/app.js",
                            text.length),
                    new SearchTarget("bundle.zip", base + "/bundle.zip",
                            archive.length));
            List<SearchRule> rules = Collections.singletonList(
                    SearchRule.custom(
                            "domain\\.example\\.org|ARCHIVE_SECRET_\\d+"));

            SearchReport report = new BucketSearchService(gateway).search(
                    targets, Collections.<String, String>emptyMap(),
                    rules, "domain.example.org", () -> false, null);

            assertEquals(2, report.getScannedFiles());
            assertEquals(1, report.getScannedArchiveEntries());
            assertEquals(2, report.getMatches().size());
            assertTrue(hasSample(report, "domain.example.org"));
            assertTrue(hasSample(report, "ARCHIVE_SECRET_123"));
            assertTrue(hasArchiveSource(report,
                    "bundle.zip!/inner/config.txt"));
        } finally {
            gateway.close();
            origin.stop(0);
        }
    }

    @Test
    public void keepsMultipleRuleFindingsForTheSameFileLink()
            throws Exception {
        byte[] text = "mail=ops@example.org; host=10.20.30.40"
                .getBytes(StandardCharsets.UTF_8);
        HttpServer origin = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/config.txt",
                exchange -> serve(exchange, text, "text/plain"));
        origin.start();
        PreviewHttpServer gateway = PreviewHttpServer.start();
        try {
            String url = "http://127.0.0.1:"
                    + origin.getAddress().getPort() + "/config.txt";
            List<SearchRule> rules = Arrays.asList(
                    SearchRule.configured("Basic", "Email",
                            "[\\w.+-]+@[\\w.-]+", "", "{0}"),
                    SearchRule.configured("Basic", "IP",
                            "10\\.20\\.30\\.40", "", "{0}"));

            SearchReport report = new BucketSearchService(gateway).search(
                    Collections.singletonList(
                            new SearchTarget("config.txt", url, text.length)),
                    Collections.<String, String>emptyMap(), rules, "",
                    () -> false, null);

            assertEquals(2, report.getMatches().size());
            assertEquals(url, report.getMatches().get(0).getSourceLink());
            assertEquals(url, report.getMatches().get(1).getSourceLink());
            assertTrue(hasRule(report, "Email"));
            assertTrue(hasRule(report, "IP"));
        } finally {
            gateway.close();
            origin.stop(0);
        }
    }

    private static boolean hasSample(SearchReport report, String sample) {
        for (SearchMatch match : report.getMatches()) {
            if (sample.equals(match.getSample())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasArchiveSource(SearchReport report,
                                            String suffix) {
        for (SearchMatch match : report.getMatches()) {
            if (match.getSourceLink().endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRule(SearchReport report, String ruleName) {
        for (SearchMatch match : report.getMatches()) {
            if (ruleName.equals(match.getRuleName())) {
                return true;
            }
        }
        return false;
    }

    private static byte[] zip(String name, String value)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(value.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void serve(HttpExchange exchange,
                              byte[] bytes,
                              String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set(
                "Content-Length", String.valueOf(bytes.length));
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
