package net.jdr2021.utils;

import org.junit.Test;

import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PreviewSizeSettingsTest {
    @Test
    public void emptyValueMeansNoConfiguredLimit() {
        OptionalLong parsed = PreviewSizeSettings.parse("  ");
        assertFalse(parsed.isPresent());
    }

    @Test
    public void parsesMegabytesAndGigabytes() {
        assertEquals(100L * 1024 * 1024,
                PreviewSizeSettings.parse("100M").getAsLong());
        assertEquals(2L * 1024 * 1024 * 1024,
                PreviewSizeSettings.parse("2g").getAsLong());
    }

    @Test
    public void rejectsValuesWithoutMOrGUnit() {
        assertInvalid("100");
        assertInvalid("5T");
        assertInvalid("0M");
        assertInvalid("1.5G");
    }

    private static void assertInvalid(String value) {
        try {
            PreviewSizeSettings.parse(value);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("100M、2G"));
            return;
        }
        throw new AssertionError("Expected invalid value: " + value);
    }
}
