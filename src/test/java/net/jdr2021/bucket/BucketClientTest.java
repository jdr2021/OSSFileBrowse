package net.jdr2021.bucket;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BucketClientTest {
    private HttpServer server;
    private URI listingUri;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> header = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serveListing);
        server.start();
        listingUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/?list-type=2&prefix=docs%2F");
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void loadsAllPagesWithSameCustomHeaders() throws IOException {
        BucketListing result = new BucketClient().loadAll(listingUri,
                Collections.singletonMap("X-Bucket-Auth", "fixture-token"));

        assertEquals(2, requests.get());
        assertEquals("fixture-token", header.get());
        assertEquals(2, result.getObjects().size());
        assertEquals("docs/one.txt", result.getObjects().get(0).getKey());
        assertEquals("docs/two.txt", result.getObjects().get(1).getKey());
    }

    @Test
    public void loadsFirstResponseWithExactlyOneRequest() throws IOException {
        BucketListing result = new BucketClient().loadFirstPage(listingUri,
                Collections.singletonMap("X-Bucket-Auth", "fixture-token"));

        assertEquals(1, requests.get());
        assertEquals("fixture-token", header.get());
        assertTrue(result.isTruncated());
        assertEquals("NEXT", result.getNextContinuationToken());
        assertEquals(1, result.getObjects().size());
        assertEquals("docs/one.txt", result.getObjects().get(0).getKey());
    }

    @Test
    public void preservesUtf8ChineseKeysWithoutDefaultCharsetDependency()
            throws IOException {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ListBucketResult xmlns=\"http://obs.myhwclouds.com/doc/2015-06-30/\">"
                    + "<Name>中文桶</Name><IsTruncated>true</IsTruncated>"
                    + "<NextMarker>thw/复件 css/i/pshow_bg_18.gif</NextMarker>"
                    + "<Contents><Key>img/weixin/二维码格式要求.txt</Key>"
                    + "<Size>321</Size></Contents></ListBucketResult>";
            byte[] body = xml.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/xml; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });

        BucketListing result = new BucketClient().loadFirstPage(
                listingUri, Collections.<String, String>emptyMap());

        assertEquals(1, requests.get());
        assertEquals("中文桶", result.getName());
        assertEquals("img/weixin/二维码格式要求.txt",
                result.getObjects().get(0).getKey());
        assertEquals("thw/复件 css/i/pshow_bg_18.gif",
                result.getNextMarker());
    }

    private void serveListing(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        header.set(exchange.getRequestHeaders().getFirst("X-Bucket-Auth"));
        boolean secondPage = exchange.getRequestURI().getRawQuery()
                .contains("continuation-token=NEXT");
        String contents = secondPage
                ? "<Contents><Key>docs/two.txt</Key><Size>2</Size></Contents>"
                : "<Contents><Key>docs/one.txt</Key><Size>1</Size></Contents>";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<ListBucketResult><Name>demo</Name><Prefix>docs/</Prefix>"
                + contents
                + "<IsTruncated>" + (!secondPage) + "</IsTruncated>"
                + (secondPage ? "" : "<NextContinuationToken>NEXT</NextContinuationToken>")
                + "</ListBucketResult>";
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
