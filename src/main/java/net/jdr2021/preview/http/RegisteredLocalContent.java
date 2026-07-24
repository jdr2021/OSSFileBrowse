package net.jdr2021.preview.http;

import java.net.URI;

/**
 * Opaque handle for a local dynamic resource tree.
 */
public final class RegisteredLocalContent {
    private final String contentId;
    private final URI baseUri;

    RegisteredLocalContent(String contentId, URI baseUri) {
        this.contentId = contentId;
        this.baseUri = baseUri;
    }

    public String getContentId() {
        return contentId;
    }

    public URI getBaseUri() {
        return baseUri;
    }

    public URI resolve(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String base = baseUri.toASCIIString();
        int query = base.indexOf('?');
        String tokenQuery = query >= 0 ? base.substring(query) : "";
        String pathBase = query >= 0 ? base.substring(0, query) : base;
        return URI.create(pathBase + normalized + tokenQuery);
    }
}
