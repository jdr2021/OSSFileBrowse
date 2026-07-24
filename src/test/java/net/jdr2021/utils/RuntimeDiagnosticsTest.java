package net.jdr2021.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeDiagnosticsTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void identifiesExpiredCertificateThroughHandshakeCauseChain() {
        SSLHandshakeException handshake = new SSLHandshakeException(
                "PKIX path validation failed");
        handshake.initCause(new CertificateExpiredException(
                "NotAfter: Mon Mar 23 07:59:59 CST 2026"));

        String message = RuntimeDiagnostics.userMessage(handshake);

        assertTrue(message.contains("HTTPS 服务器证书已过期"));
        assertTrue(message.contains("Mon Mar 23 07:59:59 CST 2026"));
        assertTrue(message.contains("中文 Key"));
    }

    @Test
    public void identifiesCertificateThatIsNotYetValid() {
        String message = RuntimeDiagnostics.userMessage(
                new CertificateNotYetValidException(
                        "NotBefore: Fri Jul 24 00:00:00 CST 2026"));

        assertTrue(message.contains("HTTPS 服务器证书尚未生效"));
        assertTrue(message.contains("系统时间"));
    }

    @Test
    public void namesDailyLogsByDomainOrIp() {
        Date date = new GregorianCalendar(
                2026, Calendar.JULY, 24, 12, 0, 0).getTime();
        assertEquals("OSSFileBrowse-20260724-example.com-log.txt",
                RuntimeDiagnostics.logFileName(
                        URI.create("https://Example.COM/files"), date));
        assertEquals("OSSFileBrowse-20260724-192.0.2.8-log.txt",
                RuntimeDiagnostics.logFileName(
                        URI.create("http://192.0.2.8:8080/"), date));
    }

    @Test
    public void appendsSameDaySameHostToOneFile() throws Exception {
        String previous = System.getProperty("ossfilebrowse.logDir");
        Path directory = temporary.newFolder("daily-host-log").toPath();
        System.setProperty("ossfilebrowse.logDir", directory.toString());
        RuntimeDiagnostics.initialize();
        Path first = null;
        try {
            URI uri = URI.create("https://append.example.test/files");
            first = RuntimeDiagnostics.bindRequestLog(uri);
            System.out.println("append-session-one");
            System.out.flush();
            RuntimeDiagnostics.resetLogBindingForTests();

            Path second = RuntimeDiagnostics.bindRequestLog(uri);
            assertEquals(first, second);
            System.out.println("append-session-two");
            System.out.flush();
            RuntimeDiagnostics.resetLogBindingForTests();

            String content = new String(
                    Files.readAllBytes(first), StandardCharsets.UTF_8);
            assertTrue(content.contains("append-session-one"));
            assertTrue(content.contains("append-session-two"));
            assertEquals("OSSFileBrowse-"
                            + new java.text.SimpleDateFormat("yyyyMMdd")
                            .format(new Date())
                            + "-append.example.test-log.txt",
                    first.getFileName().toString());
        } finally {
            RuntimeDiagnostics.resetLogBindingForTests();
            if (previous == null) {
                System.clearProperty("ossfilebrowse.logDir");
            } else {
                System.setProperty("ossfilebrowse.logDir", previous);
            }
        }
    }
}
