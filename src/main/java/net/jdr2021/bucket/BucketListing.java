package net.jdr2021.bucket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed page of an OSS/S3 compatible bucket listing.
 */
public final class BucketListing {
    private final String name;
    private final String prefix;
    private final boolean truncated;
    private final String nextMarker;
    private final String nextContinuationToken;
    private final List<BucketObject> objects;

    public BucketListing(String name,
                         String prefix,
                         boolean truncated,
                         String nextMarker,
                         String nextContinuationToken,
                         List<BucketObject> objects) {
        this.name = name;
        this.prefix = prefix;
        this.truncated = truncated;
        this.nextMarker = nextMarker;
        this.nextContinuationToken = nextContinuationToken;
        this.objects = Collections.unmodifiableList(new ArrayList<>(objects));
    }

    public String getName() {
        return name;
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public String getNextMarker() {
        return nextMarker;
    }

    public String getNextContinuationToken() {
        return nextContinuationToken;
    }

    public List<BucketObject> getObjects() {
        return objects;
    }
}
