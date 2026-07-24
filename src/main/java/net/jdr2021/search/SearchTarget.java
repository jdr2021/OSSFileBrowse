package net.jdr2021.search;

/** Immutable remote object descriptor used by the background search task. */
public final class SearchTarget {
    private final String fileName;
    private final String url;
    private final long declaredSize;

    public SearchTarget(String fileName, String url, long declaredSize) {
        this.fileName = fileName;
        this.url = url;
        this.declaredSize = declaredSize;
    }

    public String getFileName() {
        return fileName;
    }

    public String getUrl() {
        return url;
    }

    public long getDeclaredSize() {
        return declaredSize;
    }
}
