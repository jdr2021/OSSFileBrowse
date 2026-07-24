package net.jdr2021.preview.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.jdr2021.utils.TlsPolicy;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local streaming gateway used by the 2.0 preview renderers.
 *
 * <p>The server listens only on an IPv4 loopback address and allocates a
 * random port. Remote addresses and credentials stay in this Java process;
 * browser renderers receive only an opaque object id plus a per-process
 * capability token.</p>
 */
public final class PreviewHttpServer implements Closeable {

    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000;
    public static final int DEFAULT_READ_TIMEOUT_MILLIS = 30_000;
    public static final int DEFAULT_WORKER_THREADS =
            Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors() * 2));
    /**
     * Largest byte array Java can address safely for one in-memory preview.
     * Product-level limits are controlled by preview.max.size instead.
     */
    public static final int MAX_LIMITED_READ_BYTES = Integer.MAX_VALUE - 8;
    public static final int MAX_LOCAL_CONTENT_BYTES = 128 * 1024 * 1024;

    private static final int COPY_BUFFER_SIZE = 32 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int HTTP_RANGE_NOT_SATISFIABLE = 416;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern HEADER_NAME =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Pattern SINGLE_RANGE =
            Pattern.compile("^bytes=(\\d*)-(\\d*)$");
    private static final Pattern CONTENT_RANGE_TOTAL =
            Pattern.compile("^bytes\\s+(?:\\d+-\\d+|\\*)/(\\d+|\\*)$", Pattern.CASE_INSENSITIVE);

    private static final Set<String> BLOCKED_CUSTOM_HEADERS =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "host",
                    "content-length",
                    "connection",
                    "keep-alive",
                    "proxy-connection",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade",
                    "range",
                    "if-range",
                    "accept-encoding"
            )));

    private static final List<String> RESPONSE_HEADERS = Collections.unmodifiableList(Arrays.asList(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "Content-Disposition",
            "Content-Encoding",
            "ETag",
            "Last-Modified",
            "Cache-Control",
            "Expires"
    ));

    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, RemoteObject> objects =
            new ConcurrentHashMap<String, RemoteObject>();
    private final ConcurrentHashMap<String, LocalContentProvider> localContent =
            new ConcurrentHashMap<String, LocalContentProvider>();
    private final Set<HttpURLConnection> activeConnections =
            Collections.newSetFromMap(
                    new ConcurrentHashMap<HttpURLConnection, Boolean>());
    private final String token;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong proxyRequestSequence = new AtomicLong();
    private final URI baseUri;

    private PreviewHttpServer(int workerThreads,
                              int connectTimeoutMillis,
                              int readTimeoutMillis) throws IOException {
        if (workerThreads < 1) {
            throw new IllegalArgumentException("workerThreads must be positive");
        }
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.token = randomToken(32);

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        this.httpServer = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        this.executor = Executors.newFixedThreadPool(workerThreads, new GatewayThreadFactory());
        this.httpServer.setExecutor(executor);
        this.httpServer.createContext("/proxy", new ProxyHandler());
        this.httpServer.createContext("/local", new LocalContentHandler());
        this.httpServer.createContext("/status", new StatusHandler());
        this.httpServer.start();
        this.baseUri = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort());
    }

    public static PreviewHttpServer start() throws IOException {
        return new PreviewHttpServer(
                DEFAULT_WORKER_THREADS,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_READ_TIMEOUT_MILLIS);
    }

    public static PreviewHttpServer start(int workerThreads,
                                          int connectTimeoutMillis,
                                          int readTimeoutMillis) throws IOException {
        return new PreviewHttpServer(workerThreads, connectTimeoutMillis, readTimeoutMillis);
    }

    public RegisteredObject register(String remoteUrl, Map<String, String> requestHeaders) {
        ensureRunning();
        URL validatedUrl = validateRemoteUrl(remoteUrl);
        Map<String, String> safeHeaders = sanitizeHeaders(requestHeaders);

        String objectId;
        RemoteObject object = new RemoteObject(validatedUrl, safeHeaders);
        do {
            objectId = randomToken(18);
        } while (objects.putIfAbsent(objectId, object) != null);
        return new RegisteredObject(objectId, proxyUri(objectId, object));
    }

    public RegisteredObject register(String remoteUrl) {
        return register(remoteUrl, Collections.<String, String>emptyMap());
    }

    public boolean unregister(String objectId) {
        if (objectId == null) {
            return false;
        }
        return objects.remove(objectId) != null;
    }

    public RegisteredLocalContent registerLocal(LocalContentProvider provider) {
        ensureRunning();
        if (provider == null) {
            throw new IllegalArgumentException("local content provider is required");
        }
        String contentId;
        do {
            contentId = randomToken(18);
        } while (localContent.containsKey(contentId));
        localContent.put(contentId, provider);
        return new RegisteredLocalContent(contentId, localContentUri(contentId));
    }

    public boolean unregisterLocal(String contentId) {
        return contentId != null && localContent.remove(contentId) != null;
    }

    public URI getBaseUri() {
        return baseUri;
    }

    public URI getStatusUri() {
        return URI.create(baseUri.toASCIIString() + "/status?token=" + token);
    }

    public URI getProxyUri(String objectId) {
        RemoteObject object = requireObject(objectId);
        return proxyUri(objectId, object);
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getRegisteredObjectCount() {
        return objects.size();
    }

    /**
     * Detects an object's size without persisting the response. HEAD is used
     * first and a {@code bytes=0-0} request is used when HEAD has no usable
     * Content-Length.
     */
    public OptionalLong probeSize(String objectId) throws IOException {
        ensureRunning();
        RemoteObject object = requireObject(objectId);

        RemoteConnection head = null;
        try {
            head = openRemote(object, "HEAD", null, Collections.<String, String>emptyMap());
            int status = head.connection.getResponseCode();
            long length = headerLong(head.connection, "Content-Length");
            if (status >= 200 && status < 300 && length >= 0) {
                return OptionalLong.of(length);
            }
        } finally {
            closeRemote(head);
        }

        RemoteConnection range = null;
        try {
            range = openRemote(object, "GET", "bytes=0-0", Collections.<String, String>emptyMap());
            int status = range.connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_PARTIAL
                    || status == HTTP_RANGE_NOT_SATISFIABLE) {
                long total = parseContentRangeTotal(range.connection.getHeaderField("Content-Range"));
                if (total >= 0) {
                    return OptionalLong.of(total);
                }
            }
            if (status >= 200 && status < 300) {
                long length = headerLong(range.connection, "Content-Length");
                if (length >= 0) {
                    return OptionalLong.of(length);
                }
            }
            return OptionalLong.empty();
        } finally {
            closeRemote(range);
        }
    }

    /**
     * Reads no more than {@code maxBytes} into memory. One additional byte may
     * be consumed from the network solely to establish the truncated flag; it
     * is never returned to the caller.
     */
    public LimitedReadResult readAtMost(String objectId, int maxBytes) throws IOException {
        ensureRunning();
        if (maxBytes < 0 || maxBytes > MAX_LIMITED_READ_BYTES) {
            throw new IllegalArgumentException(
                    "maxBytes must be between 0 and " + MAX_LIMITED_READ_BYTES);
        }
        RemoteObject object = requireObject(objectId);
        String requestedRange = "bytes=0-" + maxBytes;
        RemoteConnection remote = null;
        try {
            remote = openRemote(object, "GET", requestedRange, Collections.<String, String>emptyMap());
            int status = remote.connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Remote server returned HTTP " + status);
            }

            long total = detectedTotalSize(remote.connection, status);
            InputStream input = responseStream(remote.connection, status);
            if (input == null) {
                return new LimitedReadResult(new byte[0], false, total);
            }
            try {
                if (total >= 0 && total <= maxBytes) {
                    int expected = (int) total;
                    byte[] exact = new byte[expected];
                    int offset = 0;
                    while (offset < expected) {
                        int read = input.read(exact, offset,
                                expected - offset);
                        if (read < 0) {
                            break;
                        }
                        if (read > 0) {
                            offset += read;
                        }
                    }
                    if (offset < expected) {
                        exact = Arrays.copyOf(exact, offset);
                    }
                    boolean truncated = input.read() >= 0
                            || total > offset;
                    return new LimitedReadResult(
                            exact, truncated, total);
                }
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream(Math.min(maxBytes, COPY_BUFFER_SIZE));
                byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int remaining = maxBytes;
                while (remaining > 0) {
                    int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (read < 0) {
                        break;
                    }
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
                boolean truncated = input.read() >= 0;
                if (!truncated && total >= 0) {
                    truncated = total > output.size();
                }
                return new LimitedReadResult(output.toByteArray(), truncated, total);
            } finally {
                input.close();
            }
        } finally {
            closeRemote(remote);
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        objects.clear();
        localContent.clear();
        httpServer.stop(0);
        for (HttpURLConnection connection : activeConnections) {
            connection.disconnect();
        }
        activeConnections.clear();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private URI proxyUri(String objectId, RemoteObject object) {
        return URI.create(baseUri.toASCIIString() + "/proxy/" + objectId
                + "/" + encodePathSegment(object.fileName)
                + "?token=" + token);
    }

    private URI localContentUri(String contentId) {
        return URI.create(baseUri.toASCIIString() + "/local/" + contentId + "/?token=" + token);
    }

    private RemoteObject requireObject(String objectId) {
        if (objectId == null || objectId.isEmpty()) {
            throw new IllegalArgumentException("objectId is required");
        }
        RemoteObject object = objects.get(objectId);
        if (object == null) {
            throw new IllegalArgumentException("Unknown objectId");
        }
        return object;
    }

    private void ensureRunning() {
        if (!running.get()) {
            throw new IllegalStateException("Preview HTTP server is closed");
        }
    }

    private RemoteConnection openRemote(RemoteObject object,
                                        String method,
                                        String range,
                                        Map<String, String> conditionalHeaders) throws IOException {
        URL current = object.url;
        URL original = object.url;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            TlsPolicy.configure(connection);
            activeConnections.add(connection);
            try {
                if (!running.get()) {
                    throw new IOException("Preview HTTP server is closed");
                }
                connection.setConnectTimeout(connectTimeoutMillis);
                connection.setReadTimeout(readTimeoutMillis);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept-Encoding", "identity");

                boolean sameOrigin = sameOrigin(original, current);
                for (Map.Entry<String, String> header : object.headers.entrySet()) {
                    if (!sameOrigin && isSensitiveHeader(header.getKey())) {
                        continue;
                    }
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                if (range != null) {
                    connection.setRequestProperty("Range", range);
                }
                for (Map.Entry<String, String> header : conditionalHeaders.entrySet()) {
                    if (header.getValue() != null && !header.getValue().isEmpty()) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                int status = connection.getResponseCode();
                if (!isRedirect(status)) {
                return new RemoteConnection(connection);
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) {
                    return new RemoteConnection(connection);
                }
                if (redirect == MAX_REDIRECTS) {
                    throw new IOException("Remote redirect limit exceeded");
                }
                URL next = new URL(current, location);
                try {
                    validateRemoteUrl(next);
                } catch (IllegalArgumentException rejected) {
                    throw new IOException("Remote redirect URL was rejected", rejected);
                }
                closeResponseStream(connection, status);
                connection.disconnect();
                activeConnections.remove(connection);
                current = next;
            } catch (IOException failure) {
                connection.disconnect();
                activeConnections.remove(connection);
                throw failure;
            } catch (RuntimeException failure) {
                connection.disconnect();
                activeConnections.remove(connection);
                throw failure;
            }
        }
        throw new IOException("Remote redirect limit exceeded");
    }

    private static Map<String, String> conditionalHeaders(Headers requestHeaders) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        copyIncomingHeader(requestHeaders, result, "If-Range");
        copyIncomingHeader(requestHeaders, result, "If-None-Match");
        copyIncomingHeader(requestHeaders, result, "If-Modified-Since");
        return result;
    }

    private static void copyIncomingHeader(Headers source,
                                           Map<String, String> destination,
                                           String name) {
        String value = source.getFirst(name);
        if (value != null) {
            destination.put(name, value);
        }
    }

    private final class ProxyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            RemoteConnection remote = null;
            boolean responseStarted = false;
            try {
                addSecurityHeaders(exchange.getResponseHeaders());
                if (!validToken(exchange.getRequestURI())) {
                    sendError(exchange, HttpURLConnection.HTTP_FORBIDDEN,
                            "Invalid preview capability token", false);
                    return;
                }
                String method = exchange.getRequestMethod();
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    sendError(exchange, HttpURLConnection.HTTP_BAD_METHOD,
                            "Only GET and HEAD are accepted", false);
                    return;
                }

                String objectId = objectIdFromPath(exchange.getRequestURI());
                RemoteObject object = objects.get(objectId);
                if (object == null) {
                    sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND,
                            "Preview session is no longer registered", false);
                    return;
                }

                long requestNumber = proxyRequestSequence.incrementAndGet();
                String range = exchange.getRequestHeaders().getFirst("Range");
                System.out.println("[预览代理#" + requestNumber + "] --> "
                        + method + " " + object.fileName
                        + "；Range=" + (range == null ? "<无>" : range));
                if (range != null && !validSingleRange(range)) {
                    System.out.println("[预览代理#" + requestNumber
                            + "] <-- 416；Range 格式未通过单区间校验");
                    exchange.getResponseHeaders().set("Content-Range", "bytes */*");
                    sendError(exchange,
                            HTTP_RANGE_NOT_SATISFIABLE,
                            "Only one valid byte range is accepted", false);
                    return;
                }

                remote = openRemote(object, method, range,
                        conditionalHeaders(exchange.getRequestHeaders()));
                HttpURLConnection connection = remote.connection;
                int status = connection.getResponseCode();
                copyResponseHeaders(connection, exchange.getResponseHeaders());
                System.out.println("[预览代理#" + requestNumber + "] <-- "
                        + status + "；Content-Type="
                        + connection.getHeaderField("Content-Type")
                        + "；Content-Length="
                        + connection.getHeaderField("Content-Length")
                        + "；Content-Range="
                        + connection.getHeaderField("Content-Range")
                        + "；Accept-Ranges="
                        + connection.getHeaderField("Accept-Ranges"));

                if (range != null && status == HttpURLConnection.HTTP_OK) {
                    exchange.getResponseHeaders().remove("Content-Range");
                    exchange.getResponseHeaders().set("Accept-Ranges", "none");
                }

                boolean noBody = "HEAD".equals(method)
                        || status == HttpURLConnection.HTTP_NOT_MODIFIED
                        || status == HttpURLConnection.HTTP_NO_CONTENT
                        || (status >= 100 && status < 200);
                if (noBody) {
                    exchange.sendResponseHeaders(status, -1);
                    responseStarted = true;
                    return;
                }

                long length = headerLong(connection, "Content-Length");
                exchange.sendResponseHeaders(status, length >= 0 ? length : 0);
                responseStarted = true;
                InputStream input = responseStream(connection, status);
                if (input == null) {
                    return;
                }
                try {
                    OutputStream output = exchange.getResponseBody();
                    try {
                        copy(input, output);
                    } finally {
                        output.close();
                    }
                } finally {
                    input.close();
                }
            } catch (IllegalArgumentException badRequest) {
                if (!responseStarted) {
                    sendError(exchange, HttpURLConnection.HTTP_BAD_REQUEST,
                            badRequest.getMessage(), false);
                }
            } catch (IOException upstreamFailure) {
                if (!responseStarted) {
                    sendError(exchange, HttpURLConnection.HTTP_BAD_GATEWAY,
                            "Remote preview request failed", false);
                }
            } finally {
                closeRemote(remote);
                exchange.close();
            }
        }
    }

    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                addSecurityHeaders(exchange.getResponseHeaders());
                if (!validToken(exchange.getRequestURI())) {
                    sendError(exchange, HttpURLConnection.HTTP_FORBIDDEN,
                            "Invalid preview capability token", true);
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    sendError(exchange, HttpURLConnection.HTTP_BAD_METHOD,
                            "Only GET is accepted", true);
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                if (!"/status".equals(path)) {
                    sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND,
                            "Status endpoint not found", true);
                    return;
                }
                String body = "{\"running\":" + running.get()
                        + ",\"registeredObjects\":" + objects.size()
                        + ",\"localContent\":" + localContent.size()
                        + ",\"port\":" + httpServer.getAddress().getPort() + "}";
                sendBytes(exchange, HttpURLConnection.HTTP_OK,
                        "application/json; charset=utf-8",
                        body.getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        }
    }

    private final class LocalContentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                addSecurityHeaders(exchange.getResponseHeaders());
                if (!validToken(exchange.getRequestURI())) {
                    sendError(exchange, HttpURLConnection.HTTP_FORBIDDEN,
                            "Invalid preview capability token", false);
                    return;
                }
                String method = exchange.getRequestMethod();
                if (!"GET".equals(method) && !"HEAD".equals(method)) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    sendError(exchange, HttpURLConnection.HTTP_BAD_METHOD,
                            "Only GET and HEAD are accepted", false);
                    return;
                }
                LocalPath localPath = localPathFromUri(exchange.getRequestURI());
                LocalContentProvider provider = localContent.get(localPath.contentId);
                if (provider == null) {
                    sendError(exchange, HttpURLConnection.HTTP_NOT_FOUND,
                            "Preview content is no longer registered", false);
                    return;
                }
                LocalContentResponse response = provider.get(localPath.relativePath);
                if (response == null) {
                    response = LocalContentResponse.notFound();
                }
                byte[] body = response.bodyForServer();
                if (body.length > MAX_LOCAL_CONTENT_BYTES) {
                    sendError(exchange, HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
                            "Local preview content exceeds limit", false);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", response.getContentType());
                exchange.getResponseHeaders().set("Cache-Control", "private, max-age=60");
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                if ("HEAD".equals(method)) {
                    exchange.getResponseHeaders().set("Content-Length",
                            String.valueOf(body.length));
                    exchange.sendResponseHeaders(response.getStatus(), -1);
                } else {
                    String range = exchange.getRequestHeaders().getFirst("Range");
                    if (range != null && response.getStatus() == HttpURLConnection.HTTP_OK) {
                        sendLocalRange(exchange, response.getContentType(), body, range);
                    } else {
                        sendBytes(exchange, response.getStatus(),
                                response.getContentType(), body);
                    }
                }
            } catch (IllegalArgumentException badRequest) {
                sendError(exchange, HttpURLConnection.HTTP_BAD_REQUEST,
                        badRequest.getMessage(), false);
            } catch (IOException generationFailure) {
                sendError(exchange, HttpURLConnection.HTTP_INTERNAL_ERROR,
                        "Local preview generation failed", false);
            } finally {
                exchange.close();
            }
        }
    }

    private boolean validToken(URI requestUri) {
        String supplied = queryParameter(requestUri.getRawQuery(), "token");
        if (supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static String objectIdFromPath(URI requestUri) {
        String rawPath = requestUri.getRawPath();
        String prefix = "/proxy/";
        if (rawPath == null || !rawPath.startsWith(prefix)
                || rawPath.length() == prefix.length()) {
            throw new IllegalArgumentException("Missing object id");
        }
        String remainder = rawPath.substring(prefix.length());
        int slash = remainder.indexOf('/');
        String segment = slash < 0 ? remainder : remainder.substring(0, slash);
        if (segment.isEmpty()
                || (slash >= 0 && remainder.indexOf('/', slash + 1) >= 0)) {
            throw new IllegalArgumentException("Invalid object id");
        }
        try {
            return URLDecoder.decode(segment, "UTF-8");
        } catch (Exception impossible) {
            throw new IllegalArgumentException("Invalid object id encoding");
        }
    }

    private static LocalPath localPathFromUri(URI requestUri) {
        String rawPath = requestUri.getRawPath();
        String prefix = "/local/";
        if (rawPath == null || !rawPath.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid local content path");
        }
        String remainder = rawPath.substring(prefix.length());
        int slash = remainder.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("Missing local content id or path");
        }
        String rawId = remainder.substring(0, slash);
        String rawRelativePath = remainder.substring(slash + 1);
        try {
            String id = URLDecoder.decode(rawId, "UTF-8");
            String relativePath = URLDecoder.decode(rawRelativePath, "UTF-8")
                    .replace('\\', '/');
            if (relativePath.startsWith("/")) {
                throw new IllegalArgumentException("Invalid local content path");
            }
            for (String segment : relativePath.split("/")) {
                if ("..".equals(segment)) {
                    throw new IllegalArgumentException("Invalid local content path");
                }
            }
            return new LocalPath(id, relativePath);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalidEncoding) {
            throw new IllegalArgumentException("Invalid local content encoding");
        }
    }

    private static String queryParameter(String rawQuery, String requestedName) {
        if (rawQuery == null) {
            return null;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int separator = pair.indexOf('=');
            String rawName = separator < 0 ? pair : pair.substring(0, separator);
            String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
            try {
                String name = URLDecoder.decode(rawName, "UTF-8");
                if (requestedName.equals(name)) {
                    return URLDecoder.decode(rawValue, "UTF-8");
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean validSingleRange(String range) {
        Matcher matcher = SINGLE_RANGE.matcher(range);
        if (!matcher.matches()) {
            return false;
        }
        String start = matcher.group(1);
        String end = matcher.group(2);
        if (start.isEmpty() && end.isEmpty()) {
            return false;
        }
        try {
            if (!start.isEmpty()) {
                long parsedStart = Long.parseLong(start);
                if (!end.isEmpty() && parsedStart > Long.parseLong(end)) {
                    return false;
                }
            } else if (Long.parseLong(end) == 0) {
                return false;
            }
            return true;
        } catch (NumberFormatException invalidNumber) {
            return false;
        }
    }

    private static String encodePathSegment(String value) {
        String name = value == null || value.isEmpty() ? "preview.bin" : value;
        try {
            return URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (Exception impossible) {
            return "preview.bin";
        }
    }

    private static void sendLocalRange(HttpExchange exchange,
                                       String contentType,
                                       byte[] body,
                                       String range) throws IOException {
        if (!validSingleRange(range) || body.length == 0) {
            exchange.getResponseHeaders().set(
                    "Content-Range", "bytes */" + body.length);
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1);
            return;
        }
        Matcher matcher = SINGLE_RANGE.matcher(range);
        if (!matcher.matches()) {
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1);
            return;
        }
        String startValue = matcher.group(1);
        String endValue = matcher.group(2);
        long start;
        long end;
        try {
            if (startValue.isEmpty()) {
                long suffix = Long.parseLong(endValue);
                start = Math.max(0, (long) body.length - suffix);
                end = body.length - 1L;
            } else {
                start = Long.parseLong(startValue);
                end = endValue.isEmpty()
                        ? body.length - 1L
                        : Math.min(Long.parseLong(endValue), body.length - 1L);
            }
        } catch (NumberFormatException invalid) {
            exchange.getResponseHeaders().set(
                    "Content-Range", "bytes */" + body.length);
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1);
            return;
        }
        if (start >= body.length || start < 0 || end < start) {
            exchange.getResponseHeaders().set(
                    "Content-Range", "bytes */" + body.length);
            exchange.sendResponseHeaders(HTTP_RANGE_NOT_SATISFIABLE, -1);
            return;
        }
        int offset = (int) start;
        int length = (int) (end - start + 1);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set(
                "Content-Range",
                "bytes " + start + "-" + end + "/" + body.length);
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_PARTIAL, length);
        OutputStream output = exchange.getResponseBody();
        try {
            output.write(body, offset, length);
        } finally {
            output.close();
        }
    }

    private static URL validateRemoteUrl(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("remoteUrl is required");
        }
        try {
            return validateRemoteUrl(new URL(remoteUrl));
        } catch (IOException invalid) {
            throw new IllegalArgumentException("remoteUrl must be a valid HTTP(S) URL", invalid);
        }
    }

    private static URL validateRemoteUrl(URL remoteUrl) {
        String protocol = remoteUrl.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new IllegalArgumentException("Only HTTP(S) remote URLs are accepted");
        }
        if (remoteUrl.getHost() == null || remoteUrl.getHost().isEmpty()) {
            throw new IllegalArgumentException("Remote URL host is required");
        }
        if (remoteUrl.getUserInfo() != null) {
            throw new IllegalArgumentException("Credentials must be supplied as request headers");
        }
        if (remoteUrl.getRef() != null) {
            throw new IllegalArgumentException("Remote URL fragments are not accepted");
        }
        return remoteUrl;
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> requestHeaders) {
        if (requestHeaders == null || requestHeaders.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        Set<String> seen = new HashSet<String>();
        for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || value == null) {
                continue;
            }
            name = name.trim();
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid request header name");
            }
            if (containsControlCharacter(value)) {
                throw new IllegalArgumentException("Invalid request header value");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (BLOCKED_CUSTOM_HEADERS.contains(lower) || seen.contains(lower)) {
                continue;
            }
            seen.add(lower);
            result.put(name, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 0x20 && character != '\t') || character == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameOrigin(URL first, URL second) {
        return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URL url) {
        return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
    }

    private static boolean isSensitiveHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return "authorization".equals(lower)
                || "cookie".equals(lower)
                || "x-api-key".equals(lower);
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    private static void copyResponseHeaders(HttpURLConnection connection, Headers response) {
        for (String name : RESPONSE_HEADERS) {
            String value = connection.getHeaderField(name);
            if (value != null) {
                response.set(name, value);
            }
        }
    }

    private static void addSecurityHeaders(Headers response) {
        response.set("X-Content-Type-Options", "nosniff");
        response.set("Referrer-Policy", "no-referrer");
        response.set("Cache-Control", "private, no-store");
    }

    private static long detectedTotalSize(HttpURLConnection connection, int status) {
        if (status == HttpURLConnection.HTTP_PARTIAL) {
            long total = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
            if (total >= 0) {
                return total;
            }
        }
        if (status == HttpURLConnection.HTTP_OK) {
            return headerLong(connection, "Content-Length");
        }
        return -1;
    }

    private static long parseContentRangeTotal(String value) {
        if (value == null) {
            return -1;
        }
        Matcher matcher = CONTENT_RANGE_TOTAL.matcher(value.trim());
        if (!matcher.matches() || "*".equals(matcher.group(1))) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static long headerLong(HttpURLConnection connection, String name) {
        String value = connection.getHeaderField(name);
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static InputStream responseStream(HttpURLConnection connection,
                                              int status) throws IOException {
        if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
            return connection.getErrorStream();
        }
        return connection.getInputStream();
    }

    private static void closeResponseStream(HttpURLConnection connection, int status) {
        try {
            InputStream input = responseStream(connection, status);
            if (input != null) {
                input.close();
            }
        } catch (IOException ignored) {
            // Closing a best-effort redirect/error stream must not hide the real response.
        }
    }

    private void closeRemote(RemoteConnection remote) {
        if (remote == null) {
            return;
        }
        int status = -1;
        try {
            status = remote.connection.getResponseCode();
        } catch (IOException ignored) {
            // There may be no readable response stream after a transport failure.
        }
        if (status >= 0) {
            closeResponseStream(remote.connection, status);
        }
        remote.connection.disconnect();
        activeConnections.remove(remote.connection);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void sendError(HttpExchange exchange,
                                  int status,
                                  String message,
                                  boolean json) throws IOException {
        String safe = message == null ? "Request failed" : message;
        if (json) {
            String body = "{\"status\":" + status + ",\"error\":\""
                    + jsonEscape(safe) + "\"}";
            sendBytes(exchange, status, "application/json; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
        } else {
            String body = "<!doctype html><html><head><meta charset=\"utf-8\">"
                    + "<title>Preview error</title></head><body><h1>Preview error</h1><p>"
                    + htmlEscape(safe) + "</p></body></html>";
            sendBytes(exchange, status, "text/html; charset=utf-8",
                    body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void sendBytes(HttpExchange exchange,
                                  int status,
                                  String contentType,
                                  byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream output = exchange.getResponseBody();
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
            }
        }
        return builder.toString();
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String randomToken(int byteCount) {
        byte[] bytes = new byte[byteCount];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class RemoteObject {
        private final URL url;
        private final Map<String, String> headers;
        private final String fileName;

        private RemoteObject(URL url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
            String path = url.getPath() == null ? "" : url.getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            this.fileName = name.isEmpty() ? "preview.bin" : name;
        }
    }

    private static final class LocalPath {
        private final String contentId;
        private final String relativePath;

        private LocalPath(String contentId, String relativePath) {
            this.contentId = contentId;
            this.relativePath = relativePath;
        }
    }

    private static final class RemoteConnection {
        private final HttpURLConnection connection;

        private RemoteConnection(HttpURLConnection connection) {
            this.connection = connection;
        }
    }

    private static final class GatewayThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "oss-preview-http-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
