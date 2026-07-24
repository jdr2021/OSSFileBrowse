package net.jdr2021.preview.http;

import java.net.URI;

/**
 * Opaque handle returned to the UI for one registered remote object.
 */
public final class RegisteredObject {

    private final String objectId;
    private final URI proxyUri;

    RegisteredObject(String objectId, URI proxyUri) {
        this.objectId = objectId;
        this.proxyUri = proxyUri;
    }

    public String getObjectId() {
        return objectId;
    }

    /**
     * Local-only URL suitable for a browser/WebView. The remote object URL and
     * its request headers are intentionally absent from this address.
     */
    public URI getProxyUri() {
        return proxyUri;
    }
}
