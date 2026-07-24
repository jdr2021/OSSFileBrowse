package net.jdr2021.utils;

import java.nio.file.Path;
import java.util.Locale;
import java.net.URI;

/**
 * Builds deterministic, cross-platform paths for artifacts produced from one
 * bucket endpoint.
 */
public final class BucketArtifactPaths {
    private BucketArtifactPaths() {
    }

    public static String hostToken(URI bucketUri) {
        if (bucketUri == null) {
            throw new IllegalArgumentException("存储桶地址为空");
        }
        String host = bucketUri.getHost();
        if (host == null || host.trim().isEmpty()) {
            host = bucketUri.getRawAuthority();
        }
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("存储桶地址缺少域名或 IP");
        }
        int userInfo = host.lastIndexOf('@');
        if (userInfo >= 0) {
            host = host.substring(userInfo + 1);
        }
        if (host.startsWith("[") && host.contains("]")) {
            host = host.substring(1, host.indexOf(']'));
        } else {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && host.indexOf(':') == colon) {
                host = host.substring(0, colon);
            }
        }
        return sanitizeToken(host.toLowerCase(Locale.ROOT), "bucket");
    }

    public static String leakReportFileName(URI bucketUri) {
        return hostToken(bucketUri) + "_leak_info.html";
    }

    public static String leakReportFileName(String hostOrIp) {
        return sanitizeToken(hostOrIp, "bucket") + "_leak_info.html";
    }

    public static Path downloadRoot(Path workingDirectory, URI bucketUri) {
        if (workingDirectory == null) {
            throw new IllegalArgumentException("工作目录为空");
        }
        return workingDirectory.toAbsolutePath().normalize()
                .resolve(hostToken(bucketUri) + "_back");
    }

    /**
     * Preserves the object-key hierarchy while making every path segment safe
     * on Windows, Linux and macOS. The containment check is retained even after
     * sanitization so a remote key never writes outside the bucket directory.
     */
    public static Path objectTarget(Path downloadRoot, String objectKey) {
        if (downloadRoot == null) {
            throw new IllegalArgumentException("下载目录为空");
        }
        String normalizedKey = objectKey == null
                ? "" : objectKey.replace('\\', '/');
        Path normalizedRoot = downloadRoot.toAbsolutePath().normalize();
        Path target = normalizedRoot;
        String[] segments = normalizedKey.split("/", -1);
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            String safe = sanitizeSegment(segment);
            target = target.resolve(safe);
        }
        target = target.toAbsolutePath().normalize();
        if (target.equals(normalizedRoot)) {
            target = normalizedRoot.resolve("download");
        }
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("对象路径超出下载目录");
        }
        return target;
    }

    private static String sanitizeSegment(String value) {
        String safe = sanitizeToken(value, "_");
        if (".".equals(safe) || "..".equals(safe)) {
            safe = safe.replace('.', '_');
        }
        String upper = safe.toUpperCase(Locale.ROOT);
        if (upper.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            safe += "_";
        }
        return safe;
    }

    private static String sanitizeToken(String value, String fallback) {
        String safe = value == null ? "" : value.trim();
        safe = safe.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_")
                .replaceAll("[. ]+$", "")
                .trim();
        return safe.isEmpty() ? fallback : safe;
    }
}
