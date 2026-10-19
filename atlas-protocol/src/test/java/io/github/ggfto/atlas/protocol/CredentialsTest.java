package io.github.ggfto.atlas.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialsTest {

    @Test
    void csrCarriesTheLocallyGeneratedPublicKey() throws Exception {
        KeyPair kp = Csr.newKeyPair();
        String pem = Csr.pem(kp, "pedido-qualquer");
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----"), pem);
        PKCS10CertificationRequest csr = Csr.parse(pem);
        assertEquals(kp.getPublic(), new org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest(csr).getPublicKey());
        assertTrue(Csr.signatureValid(csr));
    }

    @Test
    void privateKeyPemRoundTripsAndIsWrittenOwnerOnly(@TempDir Path dir) throws Exception {
        KeyPair kp = Csr.newKeyPair();
        Path key = dir.resolve("edge.key");
        Pem.writePrivateKey(key, kp.getPrivate());
        assertEquals(kp.getPrivate(), Pem.readPrivateKey(Files.readString(key)));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(key)));
    }

    @Test
    void sslContextBuildsFromPem() throws Exception {
        TestPki pki = TestPki.create();
        EdgeCredentials creds = pki.edge("edge-vps");
        assertNotNull(creds.sslContext());
    }
}
