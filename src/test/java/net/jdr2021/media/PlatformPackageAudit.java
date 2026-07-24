package net.jdr2021.media;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command-line release audit that inspects a shaded JAR without loading its
 * native code. It is therefore suitable for checking Linux and macOS
 * artifacts on the Windows release host.
 */
public final class PlatformPackageAudit {
    private static final long MAX_JAR_BYTES = 50L * 1024L * 1024L;
    private static final Pattern NATIVE_PATH = Pattern.compile(
            "^org/bytedeco/(ffmpeg|javacpp)/"
                    + "((?:windows|linux|macosx|android|ios)-[^/]+)/(.+)$");
    private static final String[] REQUIRED_FFMPEG_MODULES = {
            "avutil", "swresample", "avcodec", "avformat",
            "avdevice", "swscale", "avfilter",
            "jniavutil", "jniswresample", "jniavcodec", "jniavformat",
            "jniavdevice", "jniswscale", "jniavfilter"
    };

    private PlatformPackageAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: PlatformPackageAudit <jar> <expected-platform>");
        }
        File file = new File(args[0]).getAbsoluteFile();
        String expectedPlatform = args[1];
        if (!file.isFile()) {
            throw new IllegalStateException("JAR 文件不存在：" + file);
        }
        if (file.length() > MAX_JAR_BYTES) {
            throw new IllegalStateException(
                    "JAR 超过 50 MiB：" + file.length());
        }

        Set<String> nativePlatforms = new LinkedHashSet<>();
        boolean hasFfmpegNative = false;
        boolean hasJavaCppNative = false;
        int commandBinaries = 0;
        Set<String> ffmpegModules = new LinkedHashSet<>();
        Properties metadata = new Properties();

        try (JarFile jar = new JarFile(file)) {
            JarEntry metadataEntry =
                    jar.getJarEntry("ossfilebrowse-build.properties");
            if (metadataEntry == null) {
                throw new IllegalStateException("缺少构建平台元数据");
            }
            try (InputStream input = jar.getInputStream(metadataEntry)) {
                metadata.load(input);
            }

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                Matcher matcher = NATIVE_PATH.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                String module = matcher.group(1);
                String platform = matcher.group(2);
                String nativeName = matcher.group(3);
                nativePlatforms.add(platform);
                if ("ffmpeg".equals(module)
                        && isNativeLibrary(nativeName, expectedPlatform)) {
                    hasFfmpegNative = true;
                    for (String required : REQUIRED_FFMPEG_MODULES) {
                        if (isModuleLibrary(
                                nativeName, required, expectedPlatform)) {
                            ffmpegModules.add(required);
                        }
                    }
                }
                if ("javacpp".equals(module)
                        && isNativeLibrary(nativeName, expectedPlatform)) {
                    hasJavaCppNative = true;
                }
                if ("ffmpeg".equals(module)
                        && ("ffmpeg".equals(nativeName)
                        || "ffprobe".equals(nativeName)
                        || "ffmpeg.exe".equals(nativeName)
                        || "ffprobe.exe".equals(nativeName))) {
                    commandBinaries++;
                }
            }
        }

        require(expectedPlatform.equals(
                        metadata.getProperty("build.platform")),
                "构建元数据平台不匹配：" + metadata);
        require(nativePlatforms.size() == 1
                        && nativePlatforms.contains(expectedPlatform),
                "native 平台集合异常：" + nativePlatforms);
        require(hasFfmpegNative, "缺少 FFmpeg native 库");
        require(hasJavaCppNative, "缺少 JavaCPP native 库");
        require(ffmpegModules.size() == REQUIRED_FFMPEG_MODULES.length,
                "FFmpeg native 模块不完整：" + ffmpegModules);
        require(commandBinaries == 0,
                "发现 FFmpeg/FFprobe 命令行程序：" + commandBinaries);

        System.out.println("[package audit] platform=" + expectedPlatform
                + "; bytes=" + file.length()
                + "; nativePlatforms=" + nativePlatforms
                + "; ffmpegModules=" + ffmpegModules.size()
                + "; commandBinaries=" + commandBinaries);
    }

    private static boolean isNativeLibrary(String name, String platform) {
        if (platform.startsWith("windows-")) {
            return name.endsWith(".dll");
        }
        if (platform.startsWith("macosx-")) {
            return name.endsWith(".dylib");
        }
        return platform.startsWith("linux-")
                && (name.endsWith(".so") || name.contains(".so."));
    }

    private static boolean isModuleLibrary(String name,
                                           String module,
                                           String platform) {
        if (platform.startsWith("windows-")) {
            return name.equals(module + ".dll")
                    || name.startsWith(module + "-")
                    && name.endsWith(".dll");
        }
        return name.startsWith("lib" + module + ".")
                && isNativeLibrary(name, platform);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
