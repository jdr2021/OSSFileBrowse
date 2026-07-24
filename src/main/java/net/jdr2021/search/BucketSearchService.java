package net.jdr2021.search;

import net.jdr2021.preview.ArchiveBrowser;
import net.jdr2021.preview.FormatDetector;
import net.jdr2021.preview.PreviewFormat;
import net.jdr2021.preview.TextPreviewRenderer;
import net.jdr2021.preview.http.LimitedReadResult;
import net.jdr2021.preview.http.PreviewHttpServer;
import net.jdr2021.preview.http.RegisteredObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hssf.extractor.ExcelExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.extractor.WordExtractor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;

/**
 * Searches remote objects through the loopback gateway and recursively scans
 * supported archive entries without materializing source files on disk.
 */
public final class BucketSearchService {
    public static final int MAX_REMOTE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ENTRY_SEARCH_BYTES = 64 * 1024 * 1024;
    public static final int MAX_RESULTS = 50_000;
    public static final int MAX_MATCHES_PER_RULE_SOURCE = 1_000;
    public static final int MAX_SAMPLE_CHARS = 2_048;
    public static final int MAX_ARCHIVE_DEPTH = 4;
    private static final int MAX_EXTRACTED_CHARS = 32 * 1024 * 1024;
    private static final int FORMAT_SAMPLE_BYTES = 64 * 1024;

    private final PreviewHttpServer gateway;

    public BucketSearchService(PreviewHttpServer gateway) {
        if (gateway == null) {
            throw new IllegalArgumentException("搜索网关为空");
        }
        this.gateway = gateway;
    }

    public SearchReport search(List<SearchTarget> targets,
                               Map<String, String> requestHeaders,
                               List<SearchRule> rules,
                               String query,
                               CancellationCheck cancellation,
                               ProgressListener progress) {
        List<SearchTarget> safeTargets = targets == null
                ? Collections.<SearchTarget>emptyList() : targets;
        List<SearchRule> safeRules = rules == null
                ? Collections.<SearchRule>emptyList() : rules;
        Map<String, String> safeHeaders = requestHeaders == null
                ? Collections.<String, String>emptyMap() : requestHeaders;
        State state = new State(query, safeRules.size());
        for (int index = 0; index < safeTargets.size(); index++) {
            checkCancelled(cancellation);
            SearchTarget target = safeTargets.get(index);
            if (progress != null) {
                progress.onProgress(index + 1, safeTargets.size(),
                        target.getFileName());
            }
            scanRemote(target, safeHeaders, safeRules, state, cancellation);
        }
        return state.toReport();
    }

    private void scanRemote(SearchTarget target,
                            Map<String, String> headers,
                            List<SearchRule> rules,
                            State state,
                            CancellationCheck cancellation) {
        RegisteredObject registered = null;
        try {
            registered = gateway.register(target.getUrl(), headers);
            LimitedReadResult content = gateway.readAtMost(
                    registered.getObjectId(), MAX_REMOTE_BYTES);
            checkCancelled(cancellation);
            state.scannedFiles++;
            if (content.isTruncated()) {
                state.truncatedFiles++;
                state.warnings.add(target.getFileName()
                        + "：内容超过 64 MiB，搜索当前内存前段");
            }
            scanContent(target.getFileName(), content.getBytes(),
                    content.isTruncated(), target.getUrl(), target.getUrl(),
                    rules, state, cancellation, 0);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            state.warnings.add(target.getFileName() + "："
                    + message(failure));
            System.err.println("[搜索] 文件扫描失败：" + target.getUrl());
            failure.printStackTrace(System.err);
        } finally {
            if (registered != null) {
                gateway.unregister(registered.getObjectId());
            }
        }
    }

    private void scanContent(String fileName,
                             byte[] bytes,
                             boolean truncated,
                             String sourceLink,
                             String href,
                             List<SearchRule> rules,
                             State state,
                             CancellationCheck cancellation,
                             int depth) throws IOException {
        checkCancelled(cancellation);
        PreviewFormat format = detect(fileName, bytes);
        if (isContainer(format)) {
            if (truncated) {
                state.warnings.add(sourceLink
                        + "：压缩容器数据未完整，已跳过内部展开");
                return;
            }
            if (depth >= MAX_ARCHIVE_DEPTH) {
                state.warnings.add(sourceLink
                        + "：压缩包嵌套超过 " + MAX_ARCHIVE_DEPTH + " 层");
                return;
            }
            scanArchive(fileName, bytes, format, sourceLink, href,
                    rules, state, cancellation, depth);
            return;
        }

        String text = extractText(format, bytes);
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_EXTRACTED_CHARS) {
            text = text.substring(0, MAX_EXTRACTED_CHARS);
            state.warnings.add(sourceLink
                    + "：可搜索文本超过 32 MiB，已扫描前段");
        }
        matchRules(text, sourceLink, href, rules, state, cancellation);
    }

    private void scanArchive(String fileName,
                             byte[] bytes,
                             PreviewFormat detected,
                             String sourceLink,
                             String href,
                             List<SearchRule> rules,
                             State state,
                             CancellationCheck cancellation,
                             int depth) throws IOException {
        PreviewFormat archiveFormat = isOoxml(detected)
                ? PreviewFormat.ZIP : detected;
        ArchiveBrowser browser = ArchiveBrowser.open(
                fileName, archiveFormat, bytes);
        for (ArchiveBrowser.Entry entry : browser.getEntries()) {
            checkCancelled(cancellation);
            if (!entry.isReadable() || entry.isDirectory()) {
                continue;
            }
            String entryLink = sourceLink + "!/" + entry.getPath();
            if (entry.getSize() > MAX_ENTRY_SEARCH_BYTES) {
                state.warnings.add(entryLink
                        + "：条目超过 64 MiB，已跳过");
                continue;
            }
            try {
                byte[] entryBytes = browser.readEntry(entry);
                state.scannedArchiveEntries++;
                scanContent(entry.getPath(), entryBytes, false,
                        entryLink, href, rules, state, cancellation,
                        depth + 1);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                state.warnings.add(entryLink + "：" + message(failure));
                System.err.println("[搜索] 压缩包条目扫描失败：" + entryLink);
                failure.printStackTrace(System.err);
            }
        }
    }

    private static void matchRules(String text,
                                   String sourceLink,
                                   String href,
                                   List<SearchRule> rules,
                                   State state,
                                   CancellationCheck cancellation) {
        for (SearchRule rule : rules) {
            checkCancelled(cancellation);
            Matcher primary = rule.getPrimary().matcher(text);
            int accepted = 0;
            Set<String> unique = new HashSet<String>();
            while (primary.find()
                    && accepted < MAX_MATCHES_PER_RULE_SOURCE
                    && state.matches.size() < MAX_RESULTS) {
                checkCancelled(cancellation);
                String primaryValue = primary.group();
                if (rule.getSecondary() == null) {
                    String sample = normalizeSample(primaryValue);
                    if (!sample.isEmpty() && unique.add(sample)) {
                        state.matches.add(new SearchMatch(
                                rule.getGroup(), rule.getName(), sample,
                                sourceLink, href));
                        accepted++;
                    }
                    continue;
                }
                Matcher secondary = rule.getSecondary()
                        .matcher(primaryValue);
                while (secondary.find()
                        && accepted < MAX_MATCHES_PER_RULE_SOURCE
                        && state.matches.size() < MAX_RESULTS) {
                    String sample = normalizeSample(
                            formatSecondary(rule, secondary));
                    if (!sample.isEmpty() && unique.add(sample)) {
                        state.matches.add(new SearchMatch(
                                rule.getGroup(), rule.getName(), sample,
                                sourceLink, href));
                        accepted++;
                    }
                }
            }
            if (accepted >= MAX_MATCHES_PER_RULE_SOURCE) {
                state.warnings.add(sourceLink + " / " + rule.getName()
                        + "：匹配数量达到 1000 条上限");
            }
            if (state.matches.size() >= MAX_RESULTS) {
                state.warnings.add("搜索结果达到 50000 条上限");
                return;
            }
        }
    }

    private static String formatSecondary(SearchRule rule,
                                          Matcher matcher) {
        String format = rule.getFormat();
        if (format == null || format.trim().isEmpty()) {
            return matcher.group();
        }
        boolean capturedGroups = format.contains("{1}");
        String value = format;
        for (int index = 0; index < 10; index++) {
            String placeholder = "{" + index + "}";
            if (!value.contains(placeholder)) {
                continue;
            }
            int group = capturedGroups ? index + 1 : index;
            String replacement = group <= matcher.groupCount()
                    ? matcher.group(group) : "";
            value = value.replace(placeholder,
                    replacement == null ? "" : replacement);
        }
        return value;
    }

    private static String normalizeSample(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\u0000', ' ')
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= MAX_SAMPLE_CHARS
                ? normalized : normalized.substring(0, MAX_SAMPLE_CHARS)
                + "…";
    }

    private static PreviewFormat detect(String fileName, byte[] bytes) {
        byte[] sample;
        if (bytes.length <= FORMAT_SAMPLE_BYTES) {
            sample = bytes;
        } else {
            sample = new byte[FORMAT_SAMPLE_BYTES];
            System.arraycopy(bytes, 0, sample, 0, sample.length);
        }
        String mime = URLConnection.guessContentTypeFromName(fileName);
        return FormatDetector.detect(fileName, mime, sample);
    }

    private static boolean isContainer(PreviewFormat format) {
        return format == PreviewFormat.ZIP
                || format == PreviewFormat.JAR
                || format == PreviewFormat.TAR
                || format == PreviewFormat.SEVEN_Z
                || format == PreviewFormat.RAR
                || format == PreviewFormat.GZIP
                || isOoxml(format);
    }

    private static boolean isOoxml(PreviewFormat format) {
        return format == PreviewFormat.DOCX
                || format == PreviewFormat.XLSX
                || format == PreviewFormat.PPTX;
    }

    private static String extractText(PreviewFormat format,
                                      byte[] bytes) throws IOException {
        switch (format) {
            case IMAGE:
            case VIDEO:
            case AUDIO:
                return null;
            case PDF:
                return extractPdf(bytes);
            case DOC:
                return extractWord(bytes);
            case XLS:
                return extractExcel(bytes);
            case PPT:
                return extractPowerPoint(bytes);
            case UNKNOWN:
                if (!looksLikeText(bytes)) {
                    return null;
                }
                return TextPreviewRenderer.decode(bytes).getText();
            default:
                return TextPreviewRenderer.decode(bytes).getText();
        }
    }

    private static String extractPdf(byte[] bytes) throws IOException {
        try (PDDocument document = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String extractWord(byte[] bytes) throws IOException {
        try (WordExtractor extractor =
                     new WordExtractor(new ByteArrayInputStream(bytes))) {
            return extractor.getText();
        }
    }

    private static String extractExcel(byte[] bytes) throws IOException {
        try (HSSFWorkbook workbook =
                     new HSSFWorkbook(new ByteArrayInputStream(bytes));
             ExcelExtractor extractor = new ExcelExtractor(workbook)) {
            extractor.setIncludeSheetNames(true);
            extractor.setFormulasNotResults(false);
            extractor.setIncludeCellComments(true);
            extractor.setIncludeHeadersFooters(true);
            return extractor.getText();
        }
    }

    private static String extractPowerPoint(byte[] bytes)
            throws IOException {
        try (HSLFSlideShow presentation =
                     new HSLFSlideShow(new ByteArrayInputStream(bytes))) {
            StringBuilder text = new StringBuilder();
            for (HSLFSlide slide : presentation.getSlides()) {
                for (HSLFShape shape : slide.getShapes()) {
                    if (shape instanceof HSLFTextShape) {
                        String value = ((HSLFTextShape) shape).getText();
                        if (value != null) {
                            text.append(value).append('\n');
                        }
                    }
                }
                if (text.length() >= MAX_EXTRACTED_CHARS) {
                    break;
                }
            }
            return text.toString();
        }
    }

    private static boolean looksLikeText(byte[] bytes) {
        if (bytes.length == 0) {
            return true;
        }
        int limit = Math.min(bytes.length, 8192);
        int controls = 0;
        for (int index = 0; index < limit; index++) {
            int value = bytes[index] & 0xFF;
            if (value == 0) {
                return false;
            }
            if (value < 0x09 || (value > 0x0D && value < 0x20)) {
                controls++;
            }
        }
        return controls * 20 < limit;
    }

    private static void checkCancelled(CancellationCheck cancellation) {
        if (Thread.currentThread().isInterrupted()
                || (cancellation != null && cancellation.isCancelled())) {
            throw new CancellationException("search cancelled");
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    public interface CancellationCheck {
        boolean isCancelled();
    }

    public interface ProgressListener {
        void onProgress(int current, int total, String fileName);
    }

    private static final class State {
        private final String query;
        private final int ruleCount;
        private int scannedFiles;
        private int scannedArchiveEntries;
        private int truncatedFiles;
        private final List<SearchMatch> matches =
                new ArrayList<SearchMatch>();
        private final List<String> warnings = new ArrayList<String>();

        private State(String query, int ruleCount) {
            this.query = query;
            this.ruleCount = ruleCount;
        }

        private SearchReport toReport() {
            return new SearchReport(query, ruleCount, scannedFiles,
                    scannedArchiveEntries, truncatedFiles, matches, warnings);
        }
    }
}
