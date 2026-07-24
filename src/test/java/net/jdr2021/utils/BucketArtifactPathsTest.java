package net.jdr2021.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BucketArtifactPathsTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void namesArtifactsWithBucketDomainOrIp() throws Exception {
        assertEquals("cdn.example.com",
                BucketArtifactPaths.hostToken(
                        URI.create("https://CDN.Example.com:8443/list")));
        assertEquals("cdn.example.com_leak_info.html",
                BucketArtifactPaths.leakReportFileName(
                        URI.create("https://cdn.example.com/list")));
        assertEquals("192.168.1.10_back",
                BucketArtifactPaths.downloadRoot(
                        temporary.getRoot().toPath(),
                        URI.create("http://192.168.1.10:9000/"))
                        .getFileName().toString());
    }

    @Test
    public void preservesHierarchyAndContainsRemoteObjectPath() throws Exception {
        Path root = temporary.newFolder("bucket_back").toPath();
        Path target = BucketArtifactPaths.objectTarget(
                root, "/files/2026/../CON/report?.txt");

        assertTrue(target.startsWith(root.toAbsolutePath().normalize()));
        assertEquals(root.resolve("files/2026/_/CON_/report_.txt")
                        .toAbsolutePath().normalize(),
                target);
    }
}
