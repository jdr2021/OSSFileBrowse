package net.jdr2021.preview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.jdr2021.preview.http.LimitedReadResult;
import net.jdr2021.preview.http.PreviewHttpServer;
import net.jdr2021.preview.http.RegisteredObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RemotePreviewPipelineTest {
    private static final byte[] BODY =
            "remote preview 内容".getBytes(StandardCharsets.UTF_8);

    private HttpServer origin;
    private PreviewHttpServer gateway;
    private final AtomicReference<String> receivedHeader = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/note.txt", this::serveText);
        origin.start();
        gateway = PreviewHttpServer.start();
    }

    @After
    public void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
        if (origin != null) {
            origin.stop(0);
        }
    }

    @Test
    public void customHeaderGatewayAndRendererFormOnePreviewPipeline() throws IOException {
        RegisteredObject object = gateway.register(
                "http://127.0.0.1:" + origin.getAddress().getPort() + "/note.txt",
                Collections.singletonMap("X-Preview-Token", "fixture-secret"));

        LimitedReadResult bytes = gateway.readAtMost(object.getObjectId(), 1024);
        PreviewFormat format = FormatDetector.detect(
                "note.txt", "text/plain", bytes.getBytes());
        PreviewResult result = new PreviewService().render(new PreviewRequest(
                "note.txt", "text/plain", BODY.length, bytes.getBytes(),
                object.getProxyUri().toASCIIString(), format));

        assertEquals(PreviewFormat.TEXT, format);
        assertEquals("fixture-secret", receivedHeader.get());
        assertTrue(result.bodyAsUtf8().contains("remote preview 内容"));
        assertTrue(result.bodyAsUtf8().contains("<meta charset=\"UTF-8\">"));
    }

    private void serveText(HttpExchange exchange) throws IOException {
        receivedHeader.set(exchange.getRequestHeaders().getFirst("X-Preview-Token"));
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(BODY.length));
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, BODY.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(BODY);
        }
    }
}
