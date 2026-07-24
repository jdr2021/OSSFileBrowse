package net.jdr2021.utils;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class HttpUtilsTest {

    private HttpServer server;
    private String fixtureUrl;
    private final List<String> receivedTokens = new CopyOnWriteArrayList<>();
    private final List<String> receivedRanges = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture", new FixtureHandler());
        server.start();
        fixtureUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture";
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        receivedTokens.clear();
        receivedRanges.clear();
    }

    @Test
    public void forwardsCustomHeadersForGetHeadAndRangeReads() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Test-Token", "fixture-secret");

        assertEquals("hello", HttpUtils.httpGet(fixtureUrl, headers));
        HttpUtils.httpHead(fixtureUrl, headers);
        assertArrayEquals("ell".getBytes(StandardCharsets.UTF_8),
                HttpUtils.httpGetBytes(fixtureUrl, 1, 3, headers));

        assertEquals(3, receivedTokens.size());
        assertEquals(Collections.nCopies(3, "fixture-secret"), receivedTokens);
        assertEquals(Collections.singletonList("bytes=1-3"), receivedRanges);
    }

    @Test
    public void keepsOriginalGetApiCompatible() throws IOException {
        assertEquals("hello", HttpUtils.httpGet(fixtureUrl));
    }

    private final class FixtureHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String token = exchange.getRequestHeaders().getFirst("X-Test-Token");
            if (token != null) {
                receivedTokens.add(token);
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                receivedRanges.add(range);
            }

            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Length", "5");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            byte[] response = range == null
                    ? "hello".getBytes(StandardCharsets.UTF_8)
                    : "ell".getBytes(StandardCharsets.UTF_8);
            int status = range == null ? 200 : 206;
            if (range != null) {
                exchange.getResponseHeaders().set("Content-Range", "bytes 1-3/5");
            }
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        }
    }
}
