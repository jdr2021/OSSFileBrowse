package net.jdr2021.preview.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PreviewHttpServerTest {

    private static final byte[] BODY =
            "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

    private HttpServer origin;
    private ExecutorService originExecutor;
    private PreviewHttpServer gateway;
    private AtomicReference<String> receivedCustomHeader;
    private AtomicReference<String> receivedHostHeader;
    private AtomicReference<String> receivedContentLength;
    private AtomicInteger objectRequests;

    @Before
    public void setUp() throws Exception {
        receivedCustomHeader = new AtomicReference<String>();
        receivedHostHeader = new AtomicReference<String>();
        receivedContentLength = new AtomicReference<String>();
        objectRequests = new AtomicInteger();

        origin = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        originExecutor = Executors.newCachedThreadPool();
        origin.setExecutor(originExecutor);
        origin.createContext("/object", new RangeOriginHandler(true));
        origin.createContext("/headless", new RangeOriginHandler(false));
        origin.createContext("/ignore-range", new IgnoreRangeHandler());
        origin.start();

        gateway = PreviewHttpServer.start(4, 2_000, 5_000);
    }

    @After
    public void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
        if (origin != null) {
            origin.stop(0);
        }
        if (originExecutor != null) {
            originExecutor.shutdownNow();
        }
    }

    @Test
    public void fullProxyUsesCustomHeadersAndHidesRemoteUrl() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Preview-Auth", "secret-token");
        headers.put("Host", "malicious.invalid");
        headers.put("Content-Length", "999");
        headers.put("Connection", "close");

        RegisteredObject registered =
                gateway.register(originUrl("/object?signature=remote-secret"), headers);
        URI proxyUri = registered.getProxyUri();

        assertEquals("127.0.0.1", proxyUri.getHost());
        assertFalse(proxyUri.toASCIIString().contains("remote-secret"));
        assertFalse(proxyUri.toASCIIString().contains("signature"));
        assertTrue(proxyUri.getPath().contains(
                "/proxy/" + registered.getObjectId() + "/"));
        assertTrue(proxyUri.getPath().endsWith("object"));

        HttpURLConnection connection = open(proxyUri);
        assertEquals(200, connection.getResponseCode());
        assertEquals("application/octet-stream", connection.getContentType());
        assertEquals("inline; filename=\"fixture.bin\"",
                connection.getHeaderField("Content-Disposition"));
        assertArrayEquals(BODY, readAll(connection.getInputStream()));
        connection.disconnect();

        assertEquals("secret-token", receivedCustomHeader.get());
        assertNotEquals("malicious.invalid", receivedHostHeader.get());
        assertTrue(receivedContentLength.get() == null
                || !"999".equals(receivedContentLength.get()));
    }

    @Test
    public void proxyPathRetainsMediaFileNameExtension() {
        RegisteredObject media =
                gateway.register(originUrl("/fixture-video.mp4"));

        assertTrue(media.getProxyUri().getPath()
                .endsWith("/fixture-video.mp4"));
        assertFalse(media.getProxyUri().toASCIIString()
                .contains(originUrl("")));
    }

    @Test
    public void forwardsOneByteRangeAndResponseMetadata() throws Exception {
        RegisteredObject registered = gateway.register(originUrl("/object"));
        HttpURLConnection connection = open(registered.getProxyUri());
        connection.setRequestProperty("Range", "bytes=5-10");

        assertEquals(206, connection.getResponseCode());
        assertEquals("bytes 5-10/" + BODY.length,
                connection.getHeaderField("Content-Range"));
        assertEquals("bytes", connection.getHeaderField("Accept-Ranges"));
        assertArrayEquals(Arrays.copyOfRange(BODY, 5, 11),
                readAll(connection.getInputStream()));
        connection.disconnect();
    }

    @Test
    public void rejectsMultipleRangesBeforeContactingOrigin() throws Exception {
        RegisteredObject registered = gateway.register(originUrl("/object"));
        int before = objectRequests.get();
        HttpURLConnection connection = open(registered.getProxyUri());
        connection.setRequestProperty("Range", "bytes=0-1,4-5");

        assertEquals(416, connection.getResponseCode());
        assertEquals(before, objectRequests.get());
        assertTrue(new String(readAll(connection.getErrorStream()), StandardCharsets.UTF_8)
                .contains("one valid byte range"));
        connection.disconnect();
    }

    @Test
    public void passesFullResponseWhenOriginIgnoresRange() throws Exception {
        RegisteredObject registered = gateway.register(originUrl("/ignore-range"));
        HttpURLConnection connection = open(registered.getProxyUri());
        connection.setRequestProperty("Range", "bytes=10-12");

        assertEquals(200, connection.getResponseCode());
        assertEquals("none", connection.getHeaderField("Accept-Ranges"));
        assertArrayEquals(BODY, readAll(connection.getInputStream()));
        connection.disconnect();
    }

    @Test
    public void headIsProxiedWithoutResponseBody() throws Exception {
        RegisteredObject registered = gateway.register(originUrl("/object"));
        HttpURLConnection connection = open(registered.getProxyUri());
        connection.setRequestMethod("HEAD");

        assertEquals(200, connection.getResponseCode());
        assertEquals(BODY.length, connection.getContentLength());
        assertEquals("application/octet-stream", connection.getContentType());
        assertEquals(0, readAll(connection.getInputStream()).length);
        connection.disconnect();
    }

    @Test
    public void sizeProbeUsesHeadThenRangeFallback() throws Exception {
        RegisteredObject regular = gateway.register(originUrl("/object"));
        OptionalLong regularSize = gateway.probeSize(regular.getObjectId());
        assertTrue(regularSize.isPresent());
        assertEquals(BODY.length, regularSize.getAsLong());

        RegisteredObject headless = gateway.register(originUrl("/headless"));
        OptionalLong fallbackSize = gateway.probeSize(headless.getObjectId());
        assertTrue(fallbackSize.isPresent());
        assertEquals(BODY.length, fallbackSize.getAsLong());
    }

    @Test
    public void limitedReadReturnsAtMostLimitAndReportsOverflow() throws Exception {
        RegisteredObject registered = gateway.register(originUrl("/object"));

        LimitedReadResult limited = gateway.readAtMost(registered.getObjectId(), 5);
        assertArrayEquals(Arrays.copyOfRange(BODY, 0, 5), limited.getBytes());
        assertTrue(limited.isTruncated());
        assertEquals(BODY.length, limited.getDetectedSize());

        LimitedReadResult complete =
                gateway.readAtMost(registered.getObjectId(), BODY.length);
        assertArrayEquals(BODY, complete.getBytes());
        assertFalse(complete.isTruncated());

        RegisteredObject rangeIgnored = gateway.register(originUrl("/ignore-range"));
        LimitedReadResult ignoredRange =
                gateway.readAtMost(rangeIgnored.getObjectId(), 4);
        assertArrayEquals(Arrays.copyOfRange(BODY, 0, 4), ignoredRange.getBytes());
        assertTrue(ignoredRange.isTruncated());
        assertEquals(BODY.length, ignoredRange.getDetectedSize());

        try {
            gateway.readAtMost(registered.getObjectId(),
                    PreviewHttpServer.MAX_LIMITED_READ_BYTES + 1);
            fail("Expected a bounded-read validation error");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxBytes"));
        }
    }

    @Test
    public void bindsToIpv4LoopbackWithFreshCapabilityToken() throws Exception {
        assertEquals("127.0.0.1", gateway.getBaseUri().getHost());
        assertTrue(gateway.getBaseUri().getPort() > 0);

        PreviewHttpServer another = PreviewHttpServer.start(1, 2_000, 5_000);
        try {
            assertEquals("127.0.0.1", another.getBaseUri().getHost());
            assertNotEquals(gateway.getStatusUri().getRawQuery(),
                    another.getStatusUri().getRawQuery());
        } finally {
            another.close();
        }
    }

    @Test
    public void tokenStatusUnregisterAndCloseLifecycle() throws Exception {
        RegisteredObject registered = gateway.register(originUrl("/object"));
        assertEquals(1, gateway.getRegisteredObjectCount());

        HttpURLConnection forbidden =
                open(new URI(gateway.getBaseUri().toASCIIString()
                        + "/proxy/" + registered.getObjectId() + "?token=wrong"));
        assertEquals(403, forbidden.getResponseCode());
        forbidden.disconnect();

        HttpURLConnection status = open(gateway.getStatusUri());
        assertEquals(200, status.getResponseCode());
        String statusBody =
                new String(readAll(status.getInputStream()), StandardCharsets.UTF_8);
        assertTrue(statusBody.contains("\"running\":true"));
        assertTrue(statusBody.contains("\"registeredObjects\":1"));
        assertFalse(statusBody.contains("remote-secret"));
        status.disconnect();

        assertTrue(gateway.unregister(registered.getObjectId()));
        assertEquals(0, gateway.getRegisteredObjectCount());
        HttpURLConnection missing = open(registered.getProxyUri());
        assertEquals(404, missing.getResponseCode());
        missing.disconnect();

        gateway.close();
        gateway.close();
        assertFalse(gateway.isRunning());
        assertEquals(0, gateway.getRegisteredObjectCount());
        try {
            gateway.register(originUrl("/object"));
            fail("Expected a closed-server state error");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void invalidHeaderInjectionIsRejected() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Good", "ok\r\nX-Injected: yes");
        try {
            gateway.register(originUrl("/object"), headers);
            fail("Expected request header validation error");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("header value"));
        }
    }

    @Test
    public void servesAndReclaimsTokenProtectedLocalContent() throws Exception {
        RegisteredLocalContent local = gateway.registerLocal(relativePath ->
                LocalContentResponse.ok("text/plain; charset=UTF-8",
                        ("page:" + relativePath).getBytes(StandardCharsets.UTF_8)));

        HttpURLConnection page = open(local.resolve("page/2"));
        assertEquals(200, page.getResponseCode());
        assertEquals("page:page/2",
                new String(readAll(page.getInputStream()), StandardCharsets.UTF_8));
        page.disconnect();

        URI withoutToken = URI.create(local.resolve("page/2").toASCIIString()
                .replaceAll("\\?token=.*$", ""));
        HttpURLConnection forbidden = open(withoutToken);
        assertEquals(403, forbidden.getResponseCode());
        forbidden.disconnect();

        assertTrue(gateway.unregisterLocal(local.getContentId()));
        HttpURLConnection missing = open(local.resolve("page/2"));
        assertEquals(404, missing.getResponseCode());
        missing.disconnect();
    }

    @Test
    public void servesByteRangesForLocalArchiveMedia() throws Exception {
        RegisteredLocalContent local = gateway.registerLocal(relativePath ->
                LocalContentResponse.ok(
                        "video/mp4", BODY));

        HttpURLConnection range = open(local.resolve("content"));
        range.setRequestProperty("Range", "bytes=5-10");
        assertEquals(206, range.getResponseCode());
        assertEquals("bytes", range.getHeaderField("Accept-Ranges"));
        assertEquals("bytes 5-10/" + BODY.length,
                range.getHeaderField("Content-Range"));
        assertArrayEquals(Arrays.copyOfRange(BODY, 5, 11),
                readAll(range.getInputStream()));
        range.disconnect();

        HttpURLConnection suffix = open(local.resolve("content"));
        suffix.setRequestProperty("Range", "bytes=-4");
        assertEquals(206, suffix.getResponseCode());
        assertArrayEquals(Arrays.copyOfRange(
                        BODY, BODY.length - 4, BODY.length),
                readAll(suffix.getInputStream()));
        suffix.disconnect();

        HttpURLConnection invalid = open(local.resolve("content"));
        invalid.setRequestProperty("Range", "bytes=999-1000");
        assertEquals(416, invalid.getResponseCode());
        assertEquals("bytes */" + BODY.length,
                invalid.getHeaderField("Content-Range"));
        invalid.disconnect();
    }

    private String originUrl(String path) {
        return "http://127.0.0.1:" + origin.getAddress().getPort() + path;
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toASCIIString())
                .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private final class RangeOriginHandler implements HttpHandler {
        private final boolean supportsHead;

        private RangeOriginHandler(boolean supportsHead) {
            this.supportsHead = supportsHead;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            objectRequests.incrementAndGet();
            receivedCustomHeader.set(exchange.getRequestHeaders().getFirst("X-Preview-Auth"));
            receivedHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            receivedContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));

            Headers response = exchange.getResponseHeaders();
            response.set("Content-Type", "application/octet-stream");
            response.set("Content-Disposition", "inline; filename=\"fixture.bin\"");
            response.set("Accept-Ranges", "bytes");
            response.set("ETag", "\"fixture-etag\"");

            if ("HEAD".equals(exchange.getRequestMethod())) {
                if (!supportsHead) {
                    exchange.sendResponseHeaders(405, -1);
                } else {
                    response.set("Content-Length", String.valueOf(BODY.length));
                    exchange.sendResponseHeaders(200, -1);
                }
                exchange.close();
                return;
            }

            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                int separator = range.indexOf('-', 6);
                int start = Integer.parseInt(range.substring(6, separator));
                String endText = range.substring(separator + 1);
                int end = endText.isEmpty()
                        ? BODY.length - 1
                        : Math.min(Integer.parseInt(endText), BODY.length - 1);
                if (start >= BODY.length || start > end) {
                    response.set("Content-Range", "bytes */" + BODY.length);
                    exchange.sendResponseHeaders(416, -1);
                    exchange.close();
                    return;
                }
                byte[] slice = Arrays.copyOfRange(BODY, start, end + 1);
                response.set("Content-Range",
                        "bytes " + start + "-" + end + "/" + BODY.length);
                exchange.sendResponseHeaders(206, slice.length);
                write(exchange, slice);
                return;
            }

            exchange.sendResponseHeaders(200, BODY.length);
            write(exchange, BODY);
        }
    }

    private static final class IgnoreRangeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers response = exchange.getResponseHeaders();
            response.set("Content-Type", "application/octet-stream");
            response.set("Accept-Ranges", "bytes");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                response.set("Content-Length", String.valueOf(BODY.length));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, BODY.length);
            write(exchange, BODY);
        }
    }

    private static void write(HttpExchange exchange, byte[] bytes) throws IOException {
        OutputStream output = exchange.getResponseBody();
        try {
            output.write(bytes);
        } finally {
            output.close();
            exchange.close();
        }
    }
}
