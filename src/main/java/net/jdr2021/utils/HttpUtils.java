package net.jdr2021.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 HTTP 客户端。所有请求均使用 JRE 默认 TLS 证书链和主机名校验。
 *
 * @version 2.0
 * @Author jdr
 * @Date 2024-5-24 21:27
 */
public final class HttpUtils {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    private HttpUtils() {
    }

    public static String httpGet(String url) throws IOException {
        return httpGet(url, Collections.<String, String>emptyMap());
    }

    /**
     * 发起 GET 请求，并把自定义请求头逐项加入请求。
     */
    public static String httpGet(String url, Map<String, String> headers) throws IOException {
        return new String(httpGetBytes(url, headers), StandardCharsets.UTF_8);
    }

    public static byte[] httpGetBytes(String url) throws IOException {
        return httpGetBytes(url, Collections.<String, String>emptyMap());
    }

    /**
     * 读取响应字节，供文件预览器复用与列表请求相同的鉴权请求头。
     */
    public static byte[] httpGetBytes(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = openConnection(url, "GET", headers);
        try {
            return readSuccessfulResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    public static byte[] httpGetBytes(String url, long startInclusive, long endInclusive)
            throws IOException {
        return httpGetBytes(url, startInclusive, endInclusive,
                Collections.<String, String>emptyMap());
    }

    /**
     * 使用单段 Range 请求读取远程文件。区间两端均包含在响应中。
     */
    public static byte[] httpGetBytes(String url, long startInclusive, long endInclusive,
                                      Map<String, String> headers) throws IOException {
        if (startInclusive < 0 || endInclusive < startInclusive) {
            throw new IllegalArgumentException("Invalid byte range: "
                    + startInclusive + "-" + endInclusive);
        }
        HttpURLConnection connection = openConnection(url, "GET", headers);
        connection.setRequestProperty("Range",
                "bytes=" + startInclusive + "-" + endInclusive);
        System.out.println("[HTTP] 请求头 Range: bytes="
                + startInclusive + "-" + endInclusive);
        try {
            return readSuccessfulResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    public static Map<String, List<String>> httpHead(String url) throws IOException {
        return httpHead(url, Collections.<String, String>emptyMap());
    }

    /**
     * 发起 HEAD 请求并返回响应头，适合预览前读取长度、类型与 Range 能力。
     */
    public static Map<String, List<String>> httpHead(String url, Map<String, String> headers)
            throws IOException {
        HttpURLConnection connection = openConnection(url, "HEAD", headers);
        try {
            int statusCode = responseCode(connection);
            logResponse(connection, statusCode, -1);
            if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
                throw httpStatusException(connection, statusCode);
            }
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                List<String> values = entry.getValue() == null
                        ? Collections.<String>emptyList()
                        : new ArrayList<>(entry.getValue());
                responseHeaders.put(entry.getKey(), Collections.unmodifiableList(values));
            }
            return Collections.unmodifiableMap(responseHeaders);
        } finally {
            connection.disconnect();
        }
    }

    public static String httpPost(String url, String body) throws IOException {
        return httpPost(url, body, Collections.<String, String>emptyMap());
    }

    public static String httpPost(String url, String body, Map<String, String> headers)
            throws IOException {
        HttpURLConnection connection = openConnection(url, "POST", headers);
        try {
            if (!containsHeaderIgnoreCase(headers, "Content-Type")) {
                connection.setRequestProperty("Content-Type", "*/*");
            }
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return new String(readSuccessfulResponse(connection), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url, String method,
                                                     Map<String, String> headers)
            throws IOException {
        Map<String, String> safeHeaders = RequestHeaderPolicy.sanitize(headers);
        System.out.println("[HTTP] --> " + method + " " + url);
        logRequestHeaders(safeHeaders);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        TlsPolicy.configure(connection);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        if (!containsHeaderIgnoreCase(safeHeaders, "Accept")) {
            connection.setRequestProperty("Accept", "*/*");
        }
        if (!safeHeaders.isEmpty()) {
            for (Map.Entry<String, String> header : safeHeaders.entrySet()) {
                String name = header.getKey();
                String value = header.getValue();
                if (name != null && !name.trim().isEmpty() && value != null) {
                    connection.setRequestProperty(name.trim(), value);
                }
            }
        }
        return connection;
    }

    private static boolean containsHeaderIgnoreCase(Map<String, String> headers, String name) {
        if (headers == null) {
            return false;
        }
        for (String headerName : headers.keySet()) {
            if (headerName != null && headerName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readSuccessfulResponse(HttpURLConnection connection)
            throws IOException {
        int statusCode = responseCode(connection);
        if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            logResponse(connection, statusCode, -1);
            throw httpStatusException(connection, statusCode);
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            byte[] body = output.toByteArray();
            logResponse(connection, statusCode, body.length);
            return body;
        }
    }

    private static IOException httpStatusException(HttpURLConnection connection, int statusCode)
            throws IOException {
        String message = connection.getResponseMessage();
        String errorBody = readErrorBody(connection);
        return new IOException("HTTP " + statusCode
                + (message == null ? "" : " " + message)
                + (errorBody.isEmpty() ? "" : "；响应内容：" + errorBody));
    }

    private static int responseCode(HttpURLConnection connection) throws IOException {
        try {
            return connection.getResponseCode();
        } catch (IOException failure) {
            RuntimeDiagnostics.logFailure(
                    "HTTP 握手/响应失败 " + connection.getRequestMethod()
                            + " " + connection.getURL(), failure);
            throw failure;
        }
    }

    private static void logResponse(HttpURLConnection connection,
                                    int statusCode,
                                    long receivedBytes) {
        StringBuilder summary = new StringBuilder("[HTTP] <-- ")
                .append(statusCode).append(' ')
                .append(connection.getRequestMethod()).append(' ')
                .append(connection.getURL());
        if (receivedBytes >= 0) {
            summary.append("；接收字节=").append(receivedBytes);
        }
        summary.append("；Content-Type=")
                .append(String.valueOf(connection.getContentType()))
                .append("；Content-Encoding=")
                .append(String.valueOf(connection.getContentEncoding()))
                .append("；Content-Length=")
                .append(connection.getContentLengthLong());
        System.out.println(summary);
        for (Map.Entry<String, List<String>> header
                : connection.getHeaderFields().entrySet()) {
            String name = header.getKey() == null ? "(status)" : header.getKey();
            String values = isSensitiveHeader(name)
                    ? "<已隐藏>"
                    : String.valueOf(header.getValue());
            System.out.println("[HTTP] 响应头 " + name + ": " + values);
        }
    }

    private static void logRequestHeaders(Map<String, String> headers) {
        if (headers.isEmpty()) {
            System.out.println("[HTTP] 自定义请求头：0");
            return;
        }
        System.out.println("[HTTP] 自定义请求头：" + headers.size());
        for (Map.Entry<String, String> header : headers.entrySet()) {
            System.out.println("[HTTP] 请求头 " + header.getKey()
                    + ": <已隐藏，字符数=" + header.getValue().length() + ">");
        }
    }

    private static boolean isSensitiveHeader(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("auth")
                || lower.contains("cookie")
                || lower.contains("token")
                || lower.contains("secret")
                || lower.contains("signature")
                || lower.contains("credential");
    }

    private static String readErrorBody(HttpURLConnection connection) {
        InputStream error = connection.getErrorStream();
        if (error == null) {
            return "";
        }
        try (InputStream input = error;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int remaining = 64 * 1024;
            int count;
            while (remaining > 0
                    && (count = input.read(buffer, 0,
                    Math.min(buffer.length, remaining))) != -1) {
                output.write(buffer, 0, count);
                remaining -= count;
            }
            String body = new String(output.toByteArray(), StandardCharsets.UTF_8)
                    .replace('\r', ' ').replace('\n', ' ').trim();
            return remaining == 0 ? body + " …(已截断)" : body;
        } catch (IOException failure) {
            return "<读取错误响应失败：" + failure.getMessage() + ">";
        }
    }
}
