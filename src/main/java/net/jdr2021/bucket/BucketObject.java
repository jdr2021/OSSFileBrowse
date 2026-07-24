package net.jdr2021.bucket;

import java.time.Instant;

/**
 * A single object returned by an OSS/S3 compatible bucket listing.
 */
public final class BucketObject {
    private final String key;
    private final long size;
    private final String etag;
    private final Instant lastModified;

    public BucketObject(String key, long size, String etag, Instant lastModified) {
        this.key = key;
        this.size = size;
        this.etag = etag;
        this.lastModified = lastModified;
    }

    public String getKey() {
        return key;
    }

    public long getSize() {
        return size;
    }

    public String getEtag() {
        return etag;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public boolean isDirectoryMarker() {
        return key == null || key.endsWith("/");
    }
}
