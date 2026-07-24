package net.jdr2021.utils;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Applies the configured TLS verification policy to application-owned HTTPS
 * connections without changing the JVM-wide HttpsURLConnection defaults.
 */
public final class TlsPolicy {
    public static final String SYSTEM_PROPERTY = "ossfilebrowse.ignoreSsl";
    public static final String CONFIG_PROPERTY = "ignore.ssl";

    private static final SSLSocketFactory TRUST_ALL_SOCKET_FACTORY =
            createTrustAllSocketFactory();
    private static final HostnameVerifier TRUST_ALL_HOSTNAMES =
            (hostname, session) -> true;

    private TlsPolicy() {
    }

    public static boolean isCertificateValidationDisabled() {
        String configured = System.getProperty(SYSTEM_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            configured = ConfigLoader.getProperty(CONFIG_PROPERTY);
        }
        return configured != null && Boolean.parseBoolean(configured.trim());
    }

    public static void configure(HttpURLConnection connection) {
        if (connection instanceof HttpsURLConnection) {
            configure((HttpsURLConnection) connection,
                    isCertificateValidationDisabled());
        }
    }

    static void configure(HttpsURLConnection connection, boolean ignoreSsl) {
        if (!ignoreSsl) {
            return;
        }
        connection.setSSLSocketFactory(TRUST_ALL_SOCKET_FACTORY);
        connection.setHostnameVerifier(TRUST_ALL_HOSTNAMES);
        System.out.println("[TLS] 已忽略证书链、有效期和主机名校验："
                + connection.getURL().getHost());
    }

    private static SSLSocketFactory createTrustAllSocketFactory() {
        try {
            TrustManager[] trustAll = {new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain,
                                               String authType) {
                    // Compatibility mode trusts the presented client chain.
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain,
                                               String authType) {
                    // Compatibility mode trusts the presented server chain.
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
