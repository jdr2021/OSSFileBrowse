package net.jdr2021.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigLoaderTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void createsExternalConfigurationAndPersistsFfmpegPath()
            throws Exception {
        String previous = System.getProperty("user.dir");
        Path workingDirectory = temporary.getRoot().toPath()
                .resolve("settings");
        Files.createDirectories(workingDirectory);
        Path config = workingDirectory.resolve("config.properties");
        try {
            System.setProperty("user.dir", workingDirectory.toString());
            ConfigLoader.reloadForTests();

            assertEquals(config.toAbsolutePath().normalize(),
                    ConfigLoader.getConfigPath());
            assertTrue(Files.isRegularFile(config));
            assertEquals("true", ConfigLoader.getProperty("ignore.ssl"));
            assertEquals("", ConfigLoader.getProperty("allow.extensions"));
            assertEquals("", ConfigLoader.getProperty(
                    PreviewSizeSettings.CONFIG_PROPERTY));
            assertEquals("false",
                    ConfigLoader.getProperty("ffmpeg.enabled"));
            assertEquals("", ConfigLoader.getProperty("ffmpeg.path"));
            assertEquals("false",
                    ConfigLoader.getProperty("kkfileview.enabled"));
            assertEquals("https://file.kkview.cn/",
                    ConfigLoader.getProperty("kkFileView_URL"));

            String ffmpeg = temporary.getRoot().toPath()
                    .resolve("媒体工具").resolve("ffmpeg.exe")
                    .toString();
            ConfigLoader.setProperty("ffmpeg.path", ffmpeg);
            ConfigLoader.reloadForTests();
            assertEquals(ffmpeg, ConfigLoader.getProperty("ffmpeg.path"));

            String text = new String(
                    Files.readAllBytes(config), StandardCharsets.UTF_8);
            assertTrue(text.contains("ffmpeg.path="));
            assertTrue(text.contains("ffmpeg.enabled=false"));
            assertTrue(text.contains("kkfileview.enabled=false"));
            assertTrue(text.contains(
                    "kkFileView_URL=https\\://file.kkview.cn/"));
            assertTrue(text.contains("ignore.ssl=true"));
            assertTrue(text.contains("preview.max.size="));
        } finally {
            restore(previous);
        }
    }

    @Test
    public void defaultConfigurationLivesInCurrentWorkingDirectory()
            throws Exception {
        String previousWorkingDirectory = System.getProperty("user.dir");
        Path workingDirectory = temporary.newFolder(
                "current-working-directory").toPath();
        try {
            System.setProperty("user.dir", workingDirectory.toString());
            ConfigLoader.reloadForTests();

            Path expected = workingDirectory.toAbsolutePath().normalize()
                    .resolve("config.properties");
            assertEquals(expected, ConfigLoader.getConfigPath());
            assertTrue(Files.isRegularFile(expected));
        } finally {
            System.setProperty("user.dir", previousWorkingDirectory);
            ConfigLoader.reloadForTests();
        }
    }

    private static void restore(String previous) {
        System.setProperty("user.dir", previous);
        ConfigLoader.reloadForTests();
    }
}
