package net.jdr2021.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable aggregate produced by one bucket content search. */
public final class SearchReport {
    private final String query;
    private final int ruleCount;
    private final int scannedFiles;
    private final int scannedArchiveEntries;
    private final int truncatedFiles;
    private final List<SearchMatch> matches;
    private final List<String> warnings;

    SearchReport(String query,
                 int ruleCount,
                 int scannedFiles,
                 int scannedArchiveEntries,
                 int truncatedFiles,
                 List<SearchMatch> matches,
                 List<String> warnings) {
        this.query = query == null ? "" : query;
        this.ruleCount = ruleCount;
        this.scannedFiles = scannedFiles;
        this.scannedArchiveEntries = scannedArchiveEntries;
        this.truncatedFiles = truncatedFiles;
        this.matches = Collections.unmodifiableList(
                new ArrayList<SearchMatch>(matches));
        this.warnings = Collections.unmodifiableList(
                new ArrayList<String>(warnings));
    }

    public String getQuery() {
        return query;
    }

    public int getRuleCount() {
        return ruleCount;
    }

    public int getScannedFiles() {
        return scannedFiles;
    }

    public int getScannedArchiveEntries() {
        return scannedArchiveEntries;
    }

    public int getTruncatedFiles() {
        return truncatedFiles;
    }

    public List<SearchMatch> getMatches() {
        return matches;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
