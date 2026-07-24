package net.jdr2021.preview;

import net.jdr2021.preview.http.LocalContentProvider;
import net.jdr2021.preview.http.LocalContentResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders requested PDF pages on demand and caches the three most recent PNGs.
 */
public final class PagedPdfContentProvider implements LocalContentProvider {
    private static final Pattern PAGE_PATH = Pattern.compile("^page/(\\d+)\\.png$");
    private static final int MAX_CACHED_PAGES = 3;
    private static final float PAGE_DPI = 110f;

    private final byte[] pdf;
    private final PdfPreviewRenderer renderer;
    private final int pageCount;
    private final Map<Integer, byte[]> pageCache =
            new LinkedHashMap<Integer, byte[]>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                    return size() > MAX_CACHED_PAGES;
                }
            };

    public PagedPdfContentProvider(byte[] pdf) throws IOException {
        if (pdf == null || pdf.length == 0) {
            throw new IOException("PDF data is empty");
        }
        this.pdf = pdf;
        this.renderer = new PdfPreviewRenderer();
        this.pageCount = renderer.pageCount(pdf);
    }

    public int getPageCount() {
        return pageCount;
    }

    @Override
    public LocalContentResponse get(String relativePath) throws IOException {
        Matcher matcher = PAGE_PATH.matcher(
                relativePath == null ? "" : relativePath);
        if (!matcher.matches()) {
            return LocalContentResponse.notFound();
        }
        int page;
        try {
            page = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException invalid) {
            return LocalContentResponse.notFound();
        }
        if (page < 0 || page >= pageCount) {
            return LocalContentResponse.notFound();
        }

        byte[] png;
        synchronized (pageCache) {
            png = pageCache.get(page);
        }
        if (png == null) {
            png = renderer.renderPage(pdf, page, PAGE_DPI);
            synchronized (pageCache) {
                pageCache.put(page, png);
            }
        }
        return LocalContentResponse.ok("image/png", png);
    }
}
