package net.jdr2021.preview.http;

import java.io.IOException;

/**
 * Produces a short-lived local resource behind the gateway capability token.
 */
public interface LocalContentProvider {
    LocalContentResponse get(String relativePath) throws IOException;
}
