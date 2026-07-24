package net.jdr2021.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class RuntimePlatformTest {
    @Test
    public void mapsReleaseOperatingSystemsAndArchitectures() {
        assertEquals("windows-x86_64",
                RuntimePlatform.platformOf("Windows 11", "amd64"));
        assertEquals("linux-x86_64",
                RuntimePlatform.platformOf("Linux", "x86_64"));
        assertEquals("linux-arm64",
                RuntimePlatform.platformOf("Linux", "aarch64"));
        assertEquals("macosx-x86_64",
                RuntimePlatform.platformOf("Mac OS X", "x86_64"));
        assertEquals("macosx-arm64",
                RuntimePlatform.platformOf("Mac OS X", "arm64"));
    }

    @Test
    public void exposesFilteredBuildMetadata() {
        assertNotNull(RuntimePlatform.buildVersion());
        assertFalse(RuntimePlatform.buildVersion().isEmpty());
        assertNotNull(RuntimePlatform.buildPlatform());
        assertFalse(RuntimePlatform.buildPlatform().isEmpty());
    }
}
