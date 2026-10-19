package io.github.ggfto.atlas.protocol;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * Par de chaves EC P-256 gerado no proprio edge e o CSR do enrollment. O subject do CSR e informativo: o hub
 * ignora e assina com o CN que ele atribuiu ao edge.
 */
public final class Csr {

    private Csr() {
    }

    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    public static String pem(KeyPair kp, String subjectHint) throws OperatorCreationException, IOException {
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + subjectHint), kp.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate()));
        return Pem.block("CERTIFICATE REQUEST", csr.getEncoded());
    }

    public static PKCS10CertificationRequest parse(String pem) throws IOException {
        String begin = "-----BEGIN CERTIFICATE REQUEST-----";
        String end = "-----END CERTIFICATE REQUEST-----";
        int a = pem.indexOf(begin);
        int b = pem.indexOf(end);
        if (a < 0 || b < a) {
            throw new IOException("CSR sem bloco PEM");
        }
        return new PKCS10CertificationRequest(java.util.Base64.getMimeDecoder().decode(pem.substring(a + begin.length(), b)));
    }

    /** O CSR foi assinado pela chave privada do proprio par (prova de posse). */
    public static boolean signatureValid(PKCS10CertificationRequest csr) throws GeneralSecurityException {
        try {
            return csr.isSignatureValid(new JcaContentVerifierProviderBuilder()
                    .build(new JcaPKCS10CertificationRequest(csr).getPublicKey()));
        } catch (OperatorCreationException | PKCSException e) {
            return false;
        }
    }
}
