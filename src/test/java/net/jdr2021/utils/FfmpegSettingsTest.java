package net.jdr2021.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FfmpegSettingsTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void validatesVersionBannerAndSavesExecutable() throws Exception {
        String previous = System.getProperty("user.dir");
        Path root = temporary.getRoot().toPath();
        try {
            System.setProperty("user.dir", root.toString());
            ConfigLoader.reloadForTests();
            Path executable = createFixtureExecutable(root);

            FfmpegSettings.ProbeResult result =
                    FfmpegSettings.validateAndSave(executable);

            assertTrue(result.isValid());
            assertTrue(result.getMessage().contains("ffmpeg version"));
            assertTrue(FfmpegSettings.isEnabled());
            assertEquals(executable.toAbsolutePath().normalize(),
                    FfmpegSettings.configuredExecutable().get());
            assertEquals(executable.toAbsolutePath().normalize().toString(),
                    ConfigLoader.getProperty("ffmpeg.path"));
        } finally {
            restore(previous);
        }
    }

    @Test
    public void usesBundledDecoderForEmptyOrMissingConfiguredPath()
            throws Exception {
        String previous = System.getProperty("user.dir");
        Path root = temporary.getRoot().toPath();
        try {
            System.setProperty("user.dir", root.toString());
            ConfigLoader.reloadForTests();

            FfmpegSettings.saveConfiguredValue("");
            assertFalse(FfmpegSettings.isEnabled());
            assertTrue(FfmpegSettings.useBundledDecoder());
            assertFalse(FfmpegSettings.configuredExecutable().isPresent());

            Path missing = root.resolve("missing-ffmpeg.exe");
            FfmpegSettings.saveConfiguredValue(missing.toString());
            assertEquals(missing.toString(),
                    FfmpegSettings.configuredValue());
            assertTrue(FfmpegSettings.useBundledDecoder());
            assertFalse(FfmpegSettings.configuredExecutable().isPresent());

            FfmpegSettings.saveSettings(true, missing.toString());
            assertTrue(FfmpegSettings.isEnabled());
            assertTrue(FfmpegSettings.useBundledDecoder());
            assertFalse(FfmpegSettings.configuredExecutable().isPresent());

            FfmpegSettings.saveSettings(false, executableText(root));
            assertFalse(FfmpegSettings.isEnabled());
            assertTrue(FfmpegSettings.useBundledDecoder());
        } finally {
            restore(previous);
        }
    }

    private static String executableText(Path root) {
        return root.resolve("configured-but-disabled-ffmpeg.exe").toString();
    }

    private static Path createFixtureExecutable(Path root) throws Exception {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase().contains("win");
        Path executable = root.resolve(
                windows ? "ffmpeg-fixture.cmd" : "ffmpeg-fixture");
        String script = windows
                ? "@echo off\r\necho ffmpeg version fixture-1.0\r\nexit /b 0\r\n"
                : "#!/bin/sh\necho 'ffmpeg version fixture-1.0'\nexit 0\n";
        Files.write(executable, script.getBytes(StandardCharsets.UTF_8));
        if (!windows) {
            assertTrue(executable.toFile().setExecutable(true));
        }
        return executable;
    }

    private static void restore(String previous) {
        System.setProperty("user.dir", previous);
        ConfigLoader.reloadForTests();
    }
}
