package net.jdr2021.bucket;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the next OSS/S3 listing page while preserving unrelated query
 * parameters such as prefix, delimiter and list-type.
 */
public final class BucketPageRequestBuilder {
    private BucketPageRequestBuilder() {
    }

    public static URI nextPage(URI current, BucketListing page) {
        if (current == null || page == null || !page.isTruncated()) {
            return null;
        }
        String parameter;
        String value;
        if (page.getNextContinuationToken() != null) {
            parameter = "continuation-token";
            value = page.getNextContinuationToken();
        } else if (page.getNextMarker() != null) {
            parameter = "marker";
            value = page.getNextMarker();
        } else if (!page.getObjects().isEmpty()) {
            parameter = "marker";
            value = page.getObjects().get(page.getObjects().size() - 1).getKey();
        } else {
            throw new IllegalArgumentException("分页响应缺少下一页标记");
        }

        List<String> parameters = new ArrayList<>();
        String query = current.getRawQuery();
        if (query != null && !query.isEmpty()) {
            for (String pair : query.split("&")) {
                String name = pair;
                int equals = pair.indexOf('=');
                if (equals >= 0) {
                    name = pair.substring(0, equals);
                }
                if (!name.equalsIgnoreCase(parameter)
                        && !("marker".equals(parameter)
                        && name.equalsIgnoreCase("continuation-token"))
                        && !("continuation-token".equals(parameter)
                        && name.equalsIgnoreCase("marker"))) {
                    parameters.add(pair);
                }
            }
        }
        parameters.add(encodeQueryComponent(parameter) + "=" + encodeQueryComponent(value));

        String path = current.getRawPath() == null || current.getRawPath().isEmpty()
                ? "/" : current.getRawPath();
        return URI.create(current.getScheme() + "://" + current.getRawAuthority()
                + path + "?" + String.join("&", parameters));
    }

    private static String encodeQueryComponent(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int b = item & 0xff;
            if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z'
                    || b >= '0' && b <= '9' || b == '-' || b == '.'
                    || b == '_' || b == '~') {
                encoded.append((char) b);
            } else {
                encoded.append('%')
                        .append(Character.toUpperCase(Character.forDigit((b >>> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xf, 16)));
            }
        }
        return encoded.toString();
    }
}
