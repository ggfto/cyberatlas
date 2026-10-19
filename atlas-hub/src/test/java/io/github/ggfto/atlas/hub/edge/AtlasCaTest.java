package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.protocol.Csr;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CA propria do atlas (design §1.1): CN atribuido pelo servidor, EKU clientAuth, prova de posse da chave. */
class AtlasCaTest {

    static final AtlasCa CA = AtlasCa.generate("atlas-test-ca", Duration.ofDays(30));

    @Test
    void clientCertificateUsesTheServerAssignedNameNotTheCsrSubject() throws Exception {
        KeyPair kp = Csr.newKeyPair();
        X509Certificate c = CA.issueClient(Csr.parse(Csr.pem(kp, "edge-root-admin")), "edge-vps", Duration.ofDays(90));
        assertEquals("CN=edge-vps", c.getSubjectX500Principal().getName());
        assertEquals(kp.getPublic(), c.getPublicKey());
        assertTrue(c.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2"), "clientAuth");
        assertEquals(-1, c.getBasicConstraints(), "nao e CA");
        c.verify(CA.certificate().getPublicKey());
        long days = Duration.between(c.getNotBefore().toInstant(), c.getNotAfter().toInstant()).toDays();
        assertTrue(days >= 90 && days <= 91, String.valueOf(days));
    }

    @Test
    void csrNotSignedByItsOwnKeyIsRefused() throws Exception {
        KeyPair a = Csr.newKeyPair();
        KeyPair b = Csr.newKeyPair();
        String forged = Csr.pem(new KeyPair(a.getPublic(), b.getPrivate()), "x");
        assertThrows(EnrollmentException.class, () -> CA.issueClient(Csr.parse(forged), "edge-vps", Duration.ofDays(90)));
    }

    @Test
    void serverCertificateCarriesTheNamesTheEdgeDials() throws Exception {
        KeyPair kp = Csr.newKeyPair();
        X509Certificate s = CA.issueServer(kp.getPublic(), List.of("localhost", "atlas-hub.atlas.svc"), List.of("127.0.0.1"), Duration.ofDays(30));
        assertTrue(s.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"), "serverAuth");
        assertEquals(3, s.getSubjectAlternativeNames().size());
        s.verify(CA.certificate().getPublicKey());
    }

    @Test
    void pemRoundTripKeepsTheSameCa() throws Exception {
        AtlasCa again = AtlasCa.load(CA.certificatePem(), CA.privateKeyPem());
        KeyPair kp = Csr.newKeyPair();
        again.issueClient(Csr.parse(Csr.pem(kp, "x")), "edge-host", Duration.ofDays(1)).verify(CA.certificate().getPublicKey());
    }
}
