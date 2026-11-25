package net.jdr2021.utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 阿里云 OSS V1 签名器，支持 STS 临时凭证。
 *
 * <p>STS 与长期 AK 的区别：请求需额外携带并参与签名的
 * {@code x-oss-security-token} 头。
 *
 * <p>签名与 Date 绑定，每次请求都必须重新计算。
 */
public final class StsSigner {

    private static final String LF = "\n";

    private final String accessId;
    private final String accessKey;
    private final String securityToken;

    public StsSigner(String accessId, String accessKey, String securityToken) {
        if (isBlank(accessId) || isBlank(accessKey) || isBlank(securityToken)) {
            throw new IllegalArgumentException("STS 三要素不能为空");
        }
        this.accessId = accessId.trim();
        this.accessKey = accessKey.trim();
        this.securityToken = securityToken.trim();
    }

    public Map<String, String> signGet(String bucket, String key) {
        String resource = buildResource(bucket, key);
        String date = gmtNow();

        String stringToSign = "GET" + LF
                + LF                       // Content-MD5
                + LF                       // Content-Type
                + date + LF
                + "x-oss-security-token:" + securityToken + LF
                + resource;

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Date", date);
        headers.put("Authorization",
                "OSS " + accessId + ":" + hmacSha1Base64(accessKey, stringToSign));
        headers.put("x-oss-security-token", securityToken);
        return headers;
    }

    public Map<String, String> signForListing(java.net.URI listingUri) {
        return signGet(resolveBucket(listingUri), null);
    }

    public Map<String, String> signForObject(java.net.URI objectUri) {
        String bucket = resolveBucket(objectUri);
        return signGet(bucket, objectKey(objectUri, bucket));
    }

    public Map<String, String> signForService() {
        return signGet(null, null);
    }

    /** 校验地址可用性，原样返回。 */
    public java.net.URI logicalListingUri(java.net.URI input) {
        resolveBucket(input);
        return input;
    }

    /** 判断是否为服务端点（无桶名），此地址用于 ListBuckets。 */
    public static boolean isServiceEndpoint(java.net.URI uri) {
        String path = uri.getRawPath();
        if (path == null) {
            return false;
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return "/oss".equals(p) || "".equals(p) || "/".equals(path);
    }

    public static String objectKey(java.net.URI objectUri, String bucket) {
        // 用解码后的 path：签名针对原始 key，URL 编码仅用于传输。
        String path = objectUri.getPath();
        if (path == null) {
            return "";
        }
        if (objectUri.getHost() != null && objectUri.getHost().indexOf("oss-") > 0) {
            return path.startsWith("/") ? path.substring(1) : path;
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        if (trimmed.startsWith("oss/")) {
            trimmed = trimmed.substring("oss/".length());
        }
        if (trimmed.equals(bucket)) {
            return "";
        }
        String prefix = bucket + "/";
        if (trimmed.startsWith(prefix)) {
            return trimmed.substring(prefix.length());
        }
        return trimmed;
    }

    public static String resolveBucket(java.net.URI uri) {
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("地址缺少主机名");
        }
        // 虚拟主机风格：bucket.oss-xxx.aliyuncs.com
        if (host.indexOf('.') > 0 && host.indexOf("oss-") > 0) {
            return host.substring(0, host.indexOf('.'));
        }
        // 路径式：/bucket、/bucket/key、/oss/bucket、/oss/bucket/key
        String path = uri.getRawPath();
        if (path != null) {
            String trimmed = path.startsWith("/") ? path.substring(1) : path;
            if (trimmed.equals("oss")) {
                throw new IllegalArgumentException(
                        "地址缺少桶名，请填写 /oss/<bucket> 或 /<bucket>");
            }
            if (trimmed.startsWith("oss/")) {
                trimmed = trimmed.substring("oss/".length());
            }
            int slash = trimmed.indexOf('/');
            String bucket = slash >= 0 ? trimmed.substring(0, slash) : trimmed;
            if (!bucket.isEmpty()) {
                return bucket;
            }
        }
        throw new IllegalArgumentException("无法从地址推断存储桶名，请在 URL 里带上桶名");
    }

    private static String buildResource(String bucket, String key) {
        if (isBlank(bucket)) {
            return "/";
        }
        StringBuilder sb = new StringBuilder("/").append(bucket.trim()).append('/');
        if (!isBlank(key)) {
            String k = key.trim();
            while (k.startsWith("/")) {
                k = k.substring(1);
            }
            sb.append(k);
        }
        return sb.toString();
    }

    /** GMT 时间，格式须为 Tue, 24 Nov 2026 02:00:26 GMT。 */
    public static String gmtNow() {
        SimpleDateFormat format =
                new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }

    private static String hmacSha1Base64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes("UTF-8")));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 计算失败", e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 不受支持", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
