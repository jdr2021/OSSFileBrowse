package net.jdr2021.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Identifies the current JVM platform and the JavaCV native classifier stored
 * in the application JAR.
 */
public final class RuntimePlatform {
    private static final String BUILD_RESOURCE =
            "ossfilebrowse-build.properties";
    private static final Properties BUILD = loadBuildProperties();

    private RuntimePlatform() {
    }

    public static String currentPlatform() {
        return platformOf(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    public static String buildPlatform() {
        return BUILD.getProperty("build.platform", "unknown").trim();
    }

    public static String buildVersion() {
        return BUILD.getProperty("build.version", "unknown").trim();
    }

    public static boolean isNativeMediaCompatible() {
        return buildPlatform().equals(currentPlatform());
    }

    /**
     * Produces an early, readable error instead of allowing a mismatched JNI
     * library to fail later with an opaque linkage message.
     */
    public static void requireNativeMediaCompatible() {
        if (!isNativeMediaCompatible()) {
            throw new IllegalStateException(
                    "当前系统平台为 " + currentPlatform()
                            + "，此 JAR 内置媒体运行库为 " + buildPlatform()
                            + "；请使用与当前系统和 CPU 架构一致的发布包");
        }
    }

    static String platformOf(String osName, String osArch) {
        String os = normalize(osName);
        String arch = normalizeArchitecture(osArch);
        if (os.contains("win")) {
            return "windows-" + arch;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macosx-" + arch;
        }
        if (os.contains("linux")) {
            return "linux-" + arch;
        }
        return os.replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "") + "-" + arch;
    }

    private static String normalizeArchitecture(String value) {
        String arch = normalize(value);
        if ("amd64".equals(arch)
                || "x86-64".equals(arch)
                || "x8664".equals(arch)
                || "x64".equals(arch)) {
            return "x86_64";
        }
        if ("aarch64".equals(arch) || "arm64".equals(arch)) {
            return "arm64";
        }
        return arch.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("(^_+|_+$)", "");
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }

    private static Properties loadBuildProperties() {
        Properties properties = new Properties();
        try (InputStream input = RuntimePlatform.class.getClassLoader()
                .getResourceAsStream(BUILD_RESOURCE)) {
            if (input == null) {
                return properties;
            }
            properties.load(input);
            return properties;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "读取构建平台信息失败：" + BUILD_RESOURCE, failure);
        }
    }
}
