package net.jdr2021.preview;

import java.io.IOException;

/** Renderer extension point used by LocalGateway and optional renderer packs. */
public interface PreviewRenderer {
    boolean supports(PreviewFormat format);
    PreviewResult render(PreviewRequest request) throws IOException;
}
