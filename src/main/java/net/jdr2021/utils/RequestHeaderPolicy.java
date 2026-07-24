package net.jdr2021.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates user-defined request headers before they reach HttpURLConnection.
 * Authentication headers remain available; framing and hop-by-hop headers are
 * controlled by the HTTP client/gateway.
 */
public final class RequestHeaderPolicy {
    private static final Pattern HEADER_NAME =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Set<String> CONTROLLED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "host", "content-length", "connection", "keep-alive",
                    "proxy-authenticate", "proxy-authorization", "te", "trailer",
                    "transfer-encoding", "upgrade", "range", "if-range",
                    "accept-encoding"
            )));

    private RequestHeaderPolicy() {
    }

    public static Map<String, String> sanitize(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, HeaderValue> byLowerName = new LinkedHashMap<>();
        for (Map.Entry<String, String> item : source.entrySet()) {
            String name = item.getKey() == null ? "" : item.getKey().trim();
            String value = item.getValue();
            if (name.isEmpty() || value == null || value.trim().isEmpty()) {
                continue;
            }
            if (!HEADER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("请求头名称格式错误: " + name);
            }
            if (containsLineBreakOrNul(value)) {
                throw new IllegalArgumentException("请求头值包含控制字符: " + name);
            }
            String lowerName = name.toLowerCase(Locale.ROOT);
            if (CONTROLLED.contains(lowerName)) {
                continue;
            }
            byLowerName.remove(lowerName);
            byLowerName.put(lowerName, new HeaderValue(name, value.trim()));
        }
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        for (HeaderValue item : byLowerName.values()) {
            sanitized.put(item.name, item.value);
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static boolean containsLineBreakOrNul(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\r' || character == '\n' || character == 0) {
                return true;
            }
        }
        return false;
    }

    private static final class HeaderValue {
        private final String name;
        private final String value;

        private HeaderValue(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
