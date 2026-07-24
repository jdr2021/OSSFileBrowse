package net.jdr2021.bucket;

import net.jdr2021.utils.HttpUtils;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads OSS/S3 compatible listing responses with a stable request-header
 * snapshot. The UI uses {@link #loadFirstPage(URI, Map)} so one click maps to
 * one list request; {@link #loadAll(URI, Map)} remains available to callers
 * that explicitly need full-bucket pagination.
 */
public final class BucketClient {
    public static final int DEFAULT_MAX_PAGES = 100;
    public static final int DEFAULT_MAX_OBJECTS = 200_000;

    private final int maxPages;
    private final int maxObjects;

    public BucketClient() {
        this(DEFAULT_MAX_PAGES, DEFAULT_MAX_OBJECTS);
    }

    public BucketClient(int maxPages, int maxObjects) {
        if (maxPages < 1 || maxObjects < 1) {
            throw new IllegalArgumentException("分页数和对象数上限必须大于 0");
        }
        this.maxPages = maxPages;
        this.maxObjects = maxObjects;
    }

    /**
     * Performs exactly one HTTP request and returns every {@code Contents}
     * element carried by that response.
     */
    public BucketListing loadFirstPage(URI listingUri,
                                       Map<String, String> requestHeaders)
            throws IOException {
        validateListingUri(listingUri);
        Map<String, String> headers = snapshotHeaders(requestHeaders);
        String xml = HttpUtils.httpGet(listingUri.toASCIIString(), headers);
        System.out.println("[存储桶] UTF-8 XML 字符数：" + xml.length());
        BucketListing listing = BucketListingParser.parse(xml);
        logPageSummary(listing);
        return listing;
    }

    public BucketListing loadAll(URI listingUri, Map<String, String> requestHeaders)
            throws IOException {
        validateListingUri(listingUri);
        Map<String, String> headers = snapshotHeaders(requestHeaders);

        URI current = listingUri;
        String bucketName = null;
        String prefix = null;
        LinkedHashMap<String, BucketObject> uniqueObjects = new LinkedHashMap<>();
        for (int pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
            BucketListing page = BucketListingParser.parse(
                    HttpUtils.httpGet(current.toASCIIString(), headers));
            logPageSummary(page);
            if (bucketName == null) {
                bucketName = page.getName();
                prefix = page.getPrefix();
            }
            for (BucketObject object : page.getObjects()) {
                uniqueObjects.put(object.getKey(), object);
                if (uniqueObjects.size() > maxObjects) {
                    throw new IOException("存储桶对象数量超过 " + maxObjects + " 条上限");
                }
            }
            if (!page.isTruncated()) {
                List<BucketObject> objects = new ArrayList<>(uniqueObjects.values());
                return new BucketListing(bucketName, prefix, false,
                        null, null, objects);
            }
            current = BucketPageRequestBuilder.nextPage(current, page);
        }
        throw new IOException("存储桶分页超过 " + maxPages + " 页上限");
    }

    private static void validateListingUri(URI listingUri) {
        if (listingUri == null || !listingUri.isAbsolute()) {
            throw new IllegalArgumentException("存储桶地址必须是绝对 URI");
        }
    }

    private static Map<String, String> snapshotHeaders(
            Map<String, String> requestHeaders) {
        return requestHeaders == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(requestHeaders));
    }

    private static void logPageSummary(BucketListing listing) {
        System.out.println("[存储桶] 解析完成：Name=" + listing.getName()
                + "，Prefix=" + listing.getPrefix()
                + "，Contents=" + listing.getObjects().size()
                + "，IsTruncated=" + listing.isTruncated()
                + "，NextMarker=" + listing.getNextMarker()
                + "，NextContinuationToken="
                + listing.getNextContinuationToken());
    }
}
