package net.jdr2021.bucket;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/**
 * Builds an object URL from a ListBucket URL without leaking listing query
 * parameters into every object request.
 */
public final class ObjectUrlBuilder {
    private ObjectUrlBuilder() {
    }

    public static URI build(URI listingUri, String objectKey) {
        if (listingUri == null || objectKey == null || objectKey.isEmpty()) {
            throw new IllegalArgumentException("存储桶地址和对象 Key 均为必填项");
        }
        if (listingUri.getScheme() == null || listingUri.getRawAuthority() == null) {
            throw new IllegalArgumentException("存储桶地址必须是绝对 HTTP(S) URI");
        }
        String basePath = listingUri.getRawPath();
        if (basePath == null || basePath.isEmpty()) {
            basePath = "/";
        }
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }

        String encodedKey = encodePath(objectKey);
        String objectUri = listingUri.getScheme() + "://" + listingUri.getRawAuthority()
                + normalizeSlashes(basePath + encodedKey);
        try {
            return new URI(objectUri);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("对象地址构建失败", e);
        }
    }

    static String encodePath(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int b = item & 0xff;
            if (isUnreserved(b) || b == '/') {
                encoded.append((char) b);
            } else {
                encoded.append('%');
                char high = Character.toUpperCase(Character.forDigit((b >>> 4) & 0xf, 16));
                char low = Character.toUpperCase(Character.forDigit(b & 0xf, 16));
                encoded.append(high).append(low);
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_' || value == '~';
    }

    private static String normalizeSlashes(String path) {
        return path.replaceAll("/{2,}", "/");
    }
}
