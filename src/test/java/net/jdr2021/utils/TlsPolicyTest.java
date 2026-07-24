package net.jdr2021.utils;

import org.junit.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.URL;
import java.security.cert.Certificate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TlsPolicyTest {
    @Test
    public void bundledConfigurationEnablesCompatibilityMode() {
        String previous = System.getProperty(TlsPolicy.SYSTEM_PROPERTY);
        try {
            System.clearProperty(TlsPolicy.SYSTEM_PROPERTY);
            assertTrue(TlsPolicy.isCertificateValidationDisabled());
        } finally {
            if (previous != null) {
                System.setProperty(TlsPolicy.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    public void configuresOnlyTheSelectedHttpsConnection() throws Exception {
        FakeHttpsConnection connection = new FakeHttpsConnection();
        SSLSocketFactory originalFactory = connection.getSSLSocketFactory();

        TlsPolicy.configure(connection, true);

        assertNotSame(originalFactory, connection.getSSLSocketFactory());
        assertTrue(connection.getHostnameVerifier().verify(
                "different.example.test", null));
    }

    @Test
    public void systemPropertyCanRestoreStrictValidation() {
        String previous = System.getProperty(TlsPolicy.SYSTEM_PROPERTY);
        try {
            System.setProperty(TlsPolicy.SYSTEM_PROPERTY, "false");
            assertFalse(TlsPolicy.isCertificateValidationDisabled());
        } finally {
            if (previous == null) {
                System.clearProperty(TlsPolicy.SYSTEM_PROPERTY);
            } else {
                System.setProperty(TlsPolicy.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    public void strictModeLeavesConnectionDefaultsUntouched() throws Exception {
        FakeHttpsConnection connection = new FakeHttpsConnection();
        SSLSocketFactory originalFactory = connection.getSSLSocketFactory();

        TlsPolicy.configure(connection, false);

        assertSame(originalFactory, connection.getSSLSocketFactory());
    }

    private static final class FakeHttpsConnection extends HttpsURLConnection {
        private FakeHttpsConnection() throws Exception {
            super(new URL("https://expired.example.test/"));
        }

        @Override
        public String getCipherSuite() {
            return "TEST";
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates()
                throws SSLPeerUnverifiedException {
            throw new SSLPeerUnverifiedException("fixture");
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() throws IOException {
        }
    }
}
