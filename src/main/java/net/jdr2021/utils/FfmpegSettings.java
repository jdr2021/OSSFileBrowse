package net.jdr2021.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Validates and persists an external FFmpeg executable.
 */
public final class FfmpegSettings {
    public static final String CONFIG_PROPERTY = "ffmpeg.path";
    public static final String ENABLED_PROPERTY = "ffmpeg.enabled";
    private static final int PROBE_OUTPUT_LIMIT = 32 * 1024;
    private static final long PROBE_TIMEOUT_SECONDS = 8;

    private FfmpegSettings() {
    }

    public static Optional<Path> configuredExecutable() {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String configured = configuredValue();
        if (configured.isEmpty()) {
            return Optional.empty();
        }
        Path path;
        try {
            path = Paths.get(configured).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            return Optional.empty();
        }
        return Files.isRegularFile(path)
                ? Optional.of(path) : Optional.empty();
    }

    public static String configuredValue() {
        String value = ConfigLoader.getProperty(CONFIG_PROPERTY);
        return value == null ? "" : value.trim();
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(
                ConfigLoader.getProperty(ENABLED_PROPERTY));
    }

    /**
     * Returns {@code true} when the configured external executable is absent.
     * Media preview then uses the FFmpeg runtime bundled by JavaCV Platform.
     */
    public static boolean useBundledDecoder() {
        return !configuredExecutable().isPresent();
    }

    /**
     * Persists the editor value as entered. Keeping a missing path is useful:
     * the settings panel can display it while {@link #useBundledDecoder()}
     * keeps media preview on the bundled JavaCV route.
     */
    public static void saveConfiguredValue(String value) throws IOException {
        ConfigLoader.setProperty(CONFIG_PROPERTY,
                value == null ? "" : value.trim());
    }

    public static void saveSettings(boolean enabled, String value)
            throws IOException {
        Map<String, String> settings =
                new LinkedHashMap<String, String>();
        settings.put(ENABLED_PROPERTY, Boolean.toString(enabled));
        settings.put(CONFIG_PROPERTY,
                value == null ? "" : value.trim());
        ConfigLoader.setProperties(settings);
    }

    public static ProbeResult validateAndSave(Path executable)
            throws IOException {
        ProbeResult result = probe(executable);
        if (!result.isValid()) {
            throw new IOException(result.getMessage());
        }
        saveSettings(true, result.getExecutable().toString());
        return result;
    }

    public static ProbeResult probe(Path executable) throws IOException {
        if (executable == null) {
            throw new IOException("尚未选择 FFmpeg 程序");
        }
        Path normalized = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("FFmpeg 文件不存在：" + normalized);
        }

        Process process = probeProcessBuilder(normalized)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() ->
                readProbeOutput(process.getInputStream(), captured),
                "ffmpeg-version-reader");
        reader.setDaemon(true);
        reader.start();

        boolean completed;
        try {
            completed = process.waitFor(
                    PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("FFmpeg 检测被中断", interrupted);
        }
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("FFmpeg -version 检测超时");
        }
        try {
            reader.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        String output = new String(
                captured.toByteArray(), StandardCharsets.UTF_8).trim();
        String firstLine = firstLine(output);
        boolean valid = process.exitValue() == 0
                && output.toLowerCase().contains("ffmpeg version");
        return new ProbeResult(normalized, valid,
                valid ? firstLine : "所选程序没有返回 FFmpeg 版本信息");
    }

    private static ProcessBuilder probeProcessBuilder(Path executable) {
        String name = executable.getFileName().toString()
                .toLowerCase();
        boolean windowsScript = System.getProperty("os.name", "")
                .toLowerCase().contains("win")
                && (name.endsWith(".cmd") || name.endsWith(".bat"));
        if (windowsScript) {
            return new ProcessBuilder("cmd.exe", "/d", "/c",
                    executable.toString(), "-version");
        }
        return new ProcessBuilder(executable.toString(), "-version");
    }

    private static void readProbeOutput(InputStream input,
                                        ByteArrayOutputStream captured) {
        byte[] buffer = new byte[2 * 1024];
        int total = 0;
        try (InputStream source = input) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                int accepted = Math.min(read,
                        PROBE_OUTPUT_LIMIT - total);
                if (accepted > 0) {
                    captured.write(buffer, 0, accepted);
                    total += accepted;
                }
            }
        } catch (IOException ignored) {
            // The process exit status and captured banner provide the result.
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.replace("\r", "").trim();
    }

    public static final class ProbeResult {
        private final Path executable;
        private final boolean valid;
        private final String message;

        private ProbeResult(Path executable,
                            boolean valid,
                            String message) {
            this.executable = executable;
            this.valid = valid;
            this.message = message;
        }

        public Path getExecutable() {
            return executable;
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}
