package net.jdr2021.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KkFileViewSettingsTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void persistsExplicitEnableStateAndBuildsOriginalPreviewContract()
            throws Exception {
        String previous = System.getProperty("user.dir");
        Path root = temporary.newFolder("kk-settings").toPath();
        try {
            System.setProperty("user.dir", root.toString());
            ConfigLoader.reloadForTests();

            assertFalse(KkFileViewSettings.isEnabled());
            assertEquals(KkFileViewSettings.DEFAULT_SERVER_URL,
                    KkFileViewSettings.configuredValue());

            KkFileViewSettings.saveSettings(
                    true, "http://127.0.0.1:8012/kk");
            assertTrue(KkFileViewSettings.isEnabled());

            URI object = URI.create(
                    "https://bucket.example.test/%E4%B8%AD%E6%96%87/a.docx");
            URI preview = KkFileViewSettings.buildPreviewUri(object);
            assertEquals("http", preview.getScheme());
            assertEquals("127.0.0.1", preview.getHost());
            assertEquals("/kk/onlinePreview", preview.getPath());

            String query = preview.getRawQuery();
            String encoded = query.substring("url=".length());
            String base64 = URLDecoder.decode(encoded, "UTF-8");
            String decoded = new String(
                    Base64.getDecoder().decode(base64),
                    StandardCharsets.UTF_8);
            assertEquals(object.toASCIIString(), decoded);

            ConfigLoader.reloadForTests();
            assertTrue(KkFileViewSettings.isEnabled());
            assertEquals("http://127.0.0.1:8012/kk/",
                    KkFileViewSettings.configuredValue());
        } finally {
            restore(previous);
        }
    }

    @Test
    public void validatesServerRootAndRejectsQueryParameters() {
        assertEquals("https://kk.example.test/root/",
                KkFileViewSettings.validateServerUri(
                        "https://kk.example.test/root").toString());

        boolean rejected = false;
        try {
            KkFileViewSettings.validateServerUri(
                    "https://kk.example.test/?token=TOKEN");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    private static void restore(String previous) {
        System.setProperty("user.dir", previous);
        ConfigLoader.reloadForTests();
    }
}
