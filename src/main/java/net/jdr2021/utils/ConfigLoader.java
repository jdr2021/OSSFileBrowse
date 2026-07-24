package net.jdr2021.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads bundled defaults and overlays them with a writable external
 * {@code config.properties}. The external file is created in the current
 * working directory on first launch.
 */
public final class ConfigLoader {
    public static final String CONFIG_FILE_NAME = "config.properties";

    private static Properties properties;
    private static Path configPath;

    private ConfigLoader() {
    }

    public static synchronized String getProperty(String key) {
        ensureLoaded();
        return properties.getProperty(key);
    }

    public static synchronized Path getConfigPath() {
        ensureLoaded();
        return configPath;
    }

    public static synchronized void setProperty(String key, String value)
            throws IOException {
        Map<String, String> update = new LinkedHashMap<String, String>();
        update.put(key, value);
        setProperties(update);
    }

    /**
     * Persists a group of related settings in one atomic file replacement.
     */
    public static synchronized void setProperties(
            Map<String, String> updates) throws IOException {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (String key : updates.keySet()) {
            validateKey(key);
        }
        ensureLoaded();
        Properties revised = new Properties();
        revised.putAll(properties);
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            revised.setProperty(entry.getKey(),
                    entry.getValue() == null ? "" : entry.getValue());
        }
        persist(revised, configPath);
        properties = revised;
        System.out.println("[配置] 已保存 " + updates.keySet()
                + "；文件=" + configPath);
    }

    private static void validateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("配置项名称为空");
        }
    }

    public static synchronized void reloadForTests() {
        properties = null;
        configPath = null;
        ensureLoaded();
    }

    private static void ensureLoaded() {
        if (properties != null) {
            return;
        }
        Properties loaded = loadBundledDefaults();
        Path preferred = preferredConfigPath();
        try {
            loadOrCreateExternal(loaded, preferred);
            properties = loaded;
            configPath = preferred.toAbsolutePath().normalize();
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "当前工作目录配置文件初始化失败：" + preferred, failure);
        }
        System.out.println("[配置] 外部配置文件：" + configPath);
    }

    private static Properties loadBundledDefaults() {
        Properties defaults = new Properties();
        try (InputStream input = ConfigLoader.class.getClassLoader()
                .getResourceAsStream(CONFIG_FILE_NAME)) {
            if (input != null) {
                defaults.load(input);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("内置配置读取失败", failure);
        }
        defaults.putIfAbsent("allow.extensions", "");
        defaults.putIfAbsent(PreviewSizeSettings.CONFIG_PROPERTY, "");
        defaults.putIfAbsent("ignore.ssl", "true");
        defaults.putIfAbsent(FfmpegSettings.CONFIG_PROPERTY, "");
        defaults.putIfAbsent(FfmpegSettings.ENABLED_PROPERTY, "false");
        defaults.putIfAbsent(KkFileViewSettings.CONFIG_PROPERTY,
                KkFileViewSettings.DEFAULT_SERVER_URL);
        defaults.putIfAbsent(KkFileViewSettings.ENABLED_PROPERTY, "false");
        return defaults;
    }

    private static void loadOrCreateExternal(Properties loaded, Path path)
            throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("配置文件目录为空：" + absolute);
        }
        Files.createDirectories(parent);
        if (!Files.exists(absolute)) {
            persist(loaded, absolute);
            System.out.println("[配置] 首次运行已生成：" + absolute);
            return;
        }
        if (!Files.isRegularFile(absolute)) {
            throw new IOException("配置路径不是普通文件：" + absolute);
        }
        Properties external = new Properties();
        try (Reader reader = Files.newBufferedReader(
                absolute, StandardCharsets.UTF_8)) {
            external.load(reader);
        }
        loaded.putAll(external);
        if (!external.containsKey(PreviewSizeSettings.CONFIG_PROPERTY)) {
            String addition = System.lineSeparator()
                    + "# 文件预览大小上限；留空表示不限制；"
                    + "格式示例：100M、2G"
                    + System.lineSeparator()
                    + PreviewSizeSettings.CONFIG_PROPERTY + "="
                    + System.lineSeparator();
            Files.write(absolute, addition.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND);
            System.out.println("[配置] 已补充 "
                    + PreviewSizeSettings.CONFIG_PROPERTY
                    + "；默认值为空（不限制）");
        }
    }

    private static void persist(Properties source, Path requestedPath)
            throws IOException {
        Path path = requestedPath.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("配置文件目录为空：" + path);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".config-", ".tmp");
        boolean completed = false;
        try (Writer writer = Files.newBufferedWriter(
                temporary, StandardCharsets.UTF_8)) {
            source.store(writer,
                    "OSSFileBrowse external settings; generated automatically");
        }
        try {
            try {
                Files.move(temporary, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Path preferredConfigPath() {
        return Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize()
                .resolve(CONFIG_FILE_NAME);
    }
}
