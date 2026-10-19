package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.protocol.Csr;
import io.github.ggfto.atlas.protocol.Pem;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

/**
 * CA propria do atlas (design §1.1). Assina o certificado de cliente de cada edge com o CN que o hub atribuiu
 * (o subject do CSR e ignorado) e o certificado de servidor da porta 8443. A chave fica em Secret, com copia no
 * cofre; perder a chave so obriga a re-enrollar os edges.
 */
public final class AtlasCa {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final X509Certificate cert;
    private final PrivateKey key;

    private AtlasCa(X509Certificate cert, PrivateKey key) {
        this.cert = cert;
        this.key = key;
    }

    public static AtlasCa generate(String cn, Duration validity) {
        try {
            KeyPair kp = Csr.newKeyPair();
            X500Name name = new X500Name("CN=" + cn);
            Instant now = Instant.now();
            X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, serial(), Date.from(now.minusSeconds(60)),
                    Date.from(now.plus(validity)), name, kp.getPublic());
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            return new AtlasCa(sign(b, kp.getPrivate()), kp.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException("nao foi possivel gerar a CA", e);
        }
    }

    public static AtlasCa load(String certPem, String keyPem) throws GeneralSecurityException {
        return new AtlasCa(Pem.readCertificates(certPem).get(0), Pem.readPrivateKey(keyPem));
    }

    public X509Certificate issueClient(PKCS10CertificationRequest csr, String edgeId, Duration validity) {
        try {
            if (!Csr.signatureValid(csr)) {
                throw new EnrollmentException("CSR sem prova de posse da chave");
            }
            PublicKey pub = new JcaPKCS10CertificationRequest(csr).getPublicKey();
            X509v3CertificateBuilder b = leaf(pub, "CN=" + edgeId, validity);
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            return sign(b, key);
        } catch (EnrollmentException e) {
            throw e;
        } catch (Exception e) {
            throw new EnrollmentException("CSR invalido");
        }
    }

    public X509Certificate issueServer(PublicKey pub, List<String> dnsNames, List<String> ips, Duration validity) {
        try {
            X509v3CertificateBuilder b = leaf(pub, "CN=" + (dnsNames.isEmpty() ? "atlas-hub" : dnsNames.get(0)), validity);
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            List<GeneralName> names = new ArrayList<>();
            dnsNames.forEach(d -> names.add(new GeneralName(GeneralName.dNSName, d)));
            ips.forEach(i -> names.add(new GeneralName(GeneralName.iPAddress, i)));
            b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(new GeneralName[0])));
            return sign(b, key);
        } catch (Exception e) {
            throw new IllegalStateException("nao foi possivel emitir o certificado de servidor", e);
        }
    }

    public X509Certificate certificate() {
        return cert;
    }

    public String certificatePem() throws GeneralSecurityException {
        return Pem.certificate(cert);
    }

    public String privateKeyPem() {
        return Pem.privateKey(key);
    }

    private X509v3CertificateBuilder leaf(PublicKey pub, String subject, Duration validity) throws Exception {
        Instant now = Instant.now();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(cert, serial(), Date.from(now.minusSeconds(60)),
                Date.from(now.plus(validity)), new X500Name(subject), pub);
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyAgreement));
        return b;
    }

    private static X509Certificate sign(X509v3CertificateBuilder b, PrivateKey key) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(key)));
    }

    private static BigInteger serial() {
        return new BigInteger(120, RANDOM);
    }
}
