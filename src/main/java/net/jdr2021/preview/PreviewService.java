package net.jdr2021.preview;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe renderer registry. Custom packs are prepended, so a deployment
 * can override a core renderer without changing LocalGateway.
 */
public final class PreviewService {
    private final List<PreviewRenderer> renderers;

    public PreviewService() {
        this(Collections.<PreviewRenderer>emptyList());
    }

    public PreviewService(List<PreviewRenderer> customRenderers) {
        List<PreviewRenderer> all = new ArrayList<PreviewRenderer>();
        if (customRenderers != null) all.addAll(customRenderers);
        all.add(new PdfPreviewRenderer());
        all.add(new OoxmlPreviewRenderer());
        all.add(new ArchivePreviewRenderer());
        all.add(new TextPreviewRenderer());
        all.add(new ProxyAssetPreviewRenderer());
        all.add(new LegacyOfficePreviewRenderer());
        all.add(new UnknownPreviewRenderer());
        this.renderers = Collections.unmodifiableList(all);
    }

    public PreviewResult render(PreviewRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("request is required");
        for (PreviewRenderer renderer : renderers) {
            if (renderer.supports(request.getFormat())) {
                return renderer.render(request);
            }
        }
        return PreviewResult.html(415, HtmlPreviewRenderer.document(
                "格式提示", "<div class=\"card danger\">当前格式未注册渲染器：" +
                        HtmlPreviewRenderer.escape(String.valueOf(request.getFormat())) +
                        "</div>"), true);
    }

    public List<PreviewRenderer> getRenderers() {
        return renderers;
    }
}
