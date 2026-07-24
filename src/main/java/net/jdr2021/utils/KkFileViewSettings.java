package net.jdr2021.utils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional compatibility route for delegating every remote object preview to
 * a user-provided kkFileView server.
 */
public final class KkFileViewSettings {
    public static final String CONFIG_PROPERTY = "kkFileView_URL";
    public static final String ENABLED_PROPERTY = "kkfileview.enabled";
    public static final String DEFAULT_SERVER_URL = "https://file.kkview.cn/";
    private static final String PREVIEW_ENDPOINT = "onlinePreview?url=";

    private KkFileViewSettings() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(
                ConfigLoader.getProperty(ENABLED_PROPERTY));
    }

    public static String configuredValue() {
        String value = ConfigLoader.getProperty(CONFIG_PROPERTY);
        return value == null ? "" : value.trim();
    }

    public static URI configuredServerUri() {
        return validateServerUri(configuredValue());
    }

    public static URI validateServerUri(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "启用 kkFileView 时需要填写服务器 URL");
        }
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        final URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "kkFileView 服务器 URL 格式错误：" + normalized,
                    invalid);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme))
                || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "kkFileView 服务器 URL 需要使用 http 或 https："
                            + normalized);
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "kkFileView 服务器 URL 请填写服务根地址，不包含查询参数或片段");
        }
        return uri;
    }

    /**
     * Recreates the original kkFileView contract:
     * {@code SERVER/onlinePreview?url=BASE64(OBJECT_URL)}.
     */
    public static URI buildPreviewUri(URI objectUri) {
        if (!isEnabled()) {
            throw new IllegalStateException("kkFileView 预览尚未启用");
        }
        if (objectUri == null || !objectUri.isAbsolute()) {
            throw new IllegalArgumentException("待预览对象 URL 无效");
        }
        URI server = configuredServerUri();
        String objectUrl = objectUri.toASCIIString();
        String base64 = Base64.getEncoder().encodeToString(
                objectUrl.getBytes(StandardCharsets.UTF_8));
        try {
            String encoded = URLEncoder.encode(base64, "UTF-8");
            return URI.create(server.toASCIIString()
                    + PREVIEW_ENDPOINT + encoded);
        } catch (Exception impossible) {
            throw new IllegalStateException(
                    "kkFileView 预览 URL 编码失败", impossible);
        }
    }

    public static void saveSettings(boolean enabled, String value)
            throws IOException {
        String normalized = value == null ? "" : value.trim();
        if (enabled) {
            normalized = validateServerUri(normalized).toString();
        }
        Map<String, String> settings =
                new LinkedHashMap<String, String>();
        settings.put(ENABLED_PROPERTY, Boolean.toString(enabled));
        settings.put(CONFIG_PROPERTY, normalized);
        ConfigLoader.setProperties(settings);
    }
}
