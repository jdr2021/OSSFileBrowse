package net.jdr2021.utils;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Installs UTF-8 console/file diagnostics before JavaFX starts.
 *
 * <p>Explorer normally associates executable JAR files with {@code javaw.exe},
 * which has no visible console. Output is buffered until a bucket is selected,
 * then duplicated into one UTF-8 file per day and domain/IP. Repeated loads
 * append to the same file.</p>
 */
public final class RuntimeDiagnostics {
    private static final String UTF_8 = StandardCharsets.UTF_8.name();
    private static volatile boolean initialized;
    private static volatile Path logFile;
    private static SharedFileSink sharedFileSink;

    private RuntimeDiagnostics() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Do this before JavaFX and application classes ask for the default
        // charset. Network and persistence code still names UTF-8 explicitly.
        System.setProperty("file.encoding", UTF_8);
        refreshDefaultCharset();

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            SharedFileSink sink = new SharedFileSink();
            sharedFileSink = sink;
            System.setOut(new PrintStream(
                    new TeeOutputStream(originalOut, sink), true, UTF_8));
            System.setErr(new PrintStream(
                    new TeeOutputStream(originalErr, sink), true, UTF_8));
        } catch (Exception failure) {
            originalErr.println("初始化 UTF-8 日志缓冲区失败");
            failure.printStackTrace(originalErr);
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                logFailure("线程未捕获异常：" + thread.getName(), failure));
        printStartupReport();
    }

    public static Path getLogFile() {
        return logFile;
    }

    /**
     * Binds diagnostics to one daily file for the selected bucket host. Calls
     * for the same host on the same day keep appending to that file.
     */
    public static synchronized Path bindRequestLog(URI requestUri) {
        if (!initialized) {
            initialize();
        }
        if (requestUri == null) {
            throw new IllegalArgumentException("日志请求地址为空");
        }
        String fileName = logFileName(requestUri, new Date());
        try {
            LogTarget target = createHostLogTarget(fileName);
            Path selected = target.path.toAbsolutePath().normalize();
            if (selected.equals(logFile)) {
                target.output.close();
                return selected;
            }
            if (sharedFileSink == null) {
                target.output.close();
                throw new IOException("日志输出缓冲区尚未初始化");
            }
            sharedFileSink.bind(target.output);
            logFile = selected;
            System.out.println("[日志] 当前域名/IP日志：" + selected);
            return selected;
        } catch (IOException failure) {
            System.err.println("绑定域名/IP日志失败：" + fileName);
            failure.printStackTrace(System.err);
            return logFile;
        }
    }

    static synchronized void resetLogBindingForTests() throws IOException {
        if (sharedFileSink != null) {
            sharedFileSink.unbind();
        }
        logFile = null;
    }

    public static void logFailure(String context, Throwable failure) {
        System.err.println("[" + context + "]");
        if (failure == null) {
            System.err.println("异常对象为空");
            return;
        }
        int depth = 0;
        Throwable current = failure;
        while (current != null && depth < 32) {
            System.err.println("cause[" + depth + "]="
                    + current.getClass().getName() + ": "
                    + String.valueOf(current.getMessage()));
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        failure.printStackTrace(System.err);
    }

    public static String userMessage(Throwable failure) {
        if (failure == null) {
            return "请求执行失败";
        }
        Throwable current = failure;
        Throwable deepest = failure;
        while (current != null) {
            if (current instanceof CertificateExpiredException) {
                return "HTTPS 服务器证书已过期："
                        + nonEmptyMessage(current)
                        + "。请更新存储桶或 CDN 证书；该错误与中文 Key 编码无关";
            }
            if (current instanceof CertificateNotYetValidException) {
                return "HTTPS 服务器证书尚未生效："
                        + nonEmptyMessage(current)
                        + "。请检查系统时间和服务器证书";
            }
            deepest = current;
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return nonEmptyMessage(deepest);
    }

    private static String nonEmptyMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private static void refreshDefaultCharset() {
        try {
            // Java 8 caches the Windows ANSI code page before main() on some
            // launch paths. Clearing the cache makes the early file.encoding
            // assignment effective for legacy libraries that ask for the
            // default charset. Application-owned decoding remains explicit.
            Field field = Charset.class.getDeclaredField("defaultCharset");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Exception failure) {
            // Newer modular JDKs may restrict reflective access. Explicit
            // UTF-8 decoding and the UTF-8 PrintStreams remain active there.
            System.err.println("刷新 JVM 默认字符集缓存失败："
                    + failure.getClass().getName() + ": "
                    + failure.getMessage());
        }
    }

    private static void printStartupReport() {
        System.out.println("========== OSSFileBrowse 启动诊断 ==========");
        System.out.println("启动时间：" + new Date());
        System.out.println("Java 版本：" + System.getProperty("java.version"));
        System.out.println("Java VM：" + System.getProperty("java.vm.name"));
        System.out.println("操作系统：" + System.getProperty("os.name")
                + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        System.out.println("当前平台标识：" + RuntimePlatform.currentPlatform());
        System.out.println("应用构建版本：" + RuntimePlatform.buildVersion());
        System.out.println("随包媒体平台：" + RuntimePlatform.buildPlatform());
        System.out.println("JavaCV native 平台匹配："
                + RuntimePlatform.isNativeMediaCompatible());
        System.out.println("file.encoding：" + System.getProperty("file.encoding"));
        System.out.println("sun.jnu.encoding：" + System.getProperty("sun.jnu.encoding"));
        System.out.println("Charset.defaultCharset：" + Charset.defaultCharset().name());
        System.out.println("忽略 HTTPS 证书校验："
                + TlsPolicy.isCertificateValidationDisabled());
        System.out.println("user.dir：" + System.getProperty("user.dir"));
        System.out.println("应用位置：" + applicationLocation());
        System.out.println("配置文件：" + ConfigLoader.getConfigPath());
        System.out.println("kkFileView 启用："
                + KkFileViewSettings.isEnabled());
        System.out.println("kkFileView 服务器："
                + KkFileViewSettings.configuredValue());
        System.out.println("外部 FFmpeg 启用："
                + FfmpegSettings.isEnabled());
        System.out.println("FFmpeg 路径："
                + (FfmpegSettings.configuredValue().isEmpty()
                ? "未配置" : FfmpegSettings.configuredValue()));
        System.out.println("文件预览大小："
                + PreviewSizeSettings.description());
        System.out.println("控制台对象：" + (System.console() == null ? "未附加" : "已附加"));
        System.out.println("UTF-8 日志：等待加载存储桶后按域名/IP绑定");
        System.out.println("============================================");
    }

    static String logFileName(URI requestUri, Date date) {
        String host = requestUri.getHost();
        if (host == null || host.trim().isEmpty()) {
            host = requestUri.getRawAuthority();
        }
        if (host == null || host.trim().isEmpty()) {
            host = "unknown";
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        host = host.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_")
                .replaceAll("\\s+", "_");
        String day = new SimpleDateFormat(
                "yyyyMMdd", Locale.ROOT).format(date);
        return "OSSFileBrowse-" + day + "-" + host + "-log.txt";
    }

    private static LogTarget createHostLogTarget(String fileName)
            throws IOException {
        String configured = System.getProperty("ossfilebrowse.logDir");
        Path preferredDirectory = configured == null || configured.trim().isEmpty()
                ? applicationDirectory().resolve("logs")
                : Paths.get(configured.trim());
        try {
            return openLogTarget(preferredDirectory.resolve(fileName));
        } catch (IOException preferredFailure) {
            Path fallback = Paths.get(System.getProperty("user.home"),
                    ".ossfilebrowse", "logs");
            return openLogTarget(fallback.resolve(fileName));
        }
    }

    private static LogTarget openLogTarget(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                path, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND));
        return new LogTarget(path, output);
    }

    private static Path applicationDirectory() {
        try {
            URI uri = RuntimeDiagnostics.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path location = Paths.get(uri).toAbsolutePath().normalize();
            return Files.isRegularFile(location)
                    ? location.getParent()
                    : Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath().normalize();
        }
    }

    private static String applicationLocation() {
        try {
            return RuntimeDiagnostics.class.getProtectionDomain()
                    .getCodeSource().getLocation().toExternalForm();
        } catch (RuntimeException failure) {
            return "unknown: " + failure.getMessage();
        }
    }

    private static final class SharedFileSink {
        private final ByteArrayOutputStream pending =
                new ByteArrayOutputStream(16 * 1024);
        private OutputStream output;

        private SharedFileSink() {
        }

        private synchronized void bind(OutputStream requested)
                throws IOException {
            if (output != null) {
                output.flush();
                output.close();
            }
            output = requested;
            pending.writeTo(output);
            pending.reset();
            output.flush();
        }

        private synchronized void unbind() throws IOException {
            if (output != null) {
                output.flush();
                output.close();
                output = null;
            }
        }

        private synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            if (output == null) {
                pending.write(bytes, offset, length);
            } else {
                output.write(bytes, offset, length);
            }
        }

        private synchronized void flush() throws IOException {
            if (output != null) {
                output.flush();
            }
        }
    }

    private static final class LogTarget {
        private final Path path;
        private final OutputStream output;

        private LogTarget(Path path, OutputStream output) {
            this.path = path;
            this.output = output;
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream console;
        private final SharedFileSink file;

        private TeeOutputStream(OutputStream console, SharedFileSink file) {
            this.console = console;
            this.file = file;
        }

        @Override
        public void write(int value) throws IOException {
            byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            console.write(bytes, offset, length);
            file.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            console.flush();
            file.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
