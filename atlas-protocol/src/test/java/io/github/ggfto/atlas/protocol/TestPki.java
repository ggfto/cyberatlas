package io.github.ggfto.atlas.protocol;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** CA de teste minima para os testes do protocolo (a CA de verdade mora no hub). */
record TestPki(KeyPair caKey, X509Certificate ca) {

    static TestPki create() throws Exception {
        KeyPair kp = Csr.newKeyPair();
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=atlas-test-ca");
        var b = new JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date.from(now.minusSeconds(60)),
                Date.from(now.plus(Duration.ofDays(1))), name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        X509Certificate ca = new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate())));
        return new TestPki(kp, ca);
    }

    EdgeCredentials edge(String cn) throws Exception {
        KeyPair kp = Csr.newKeyPair();
        Instant now = Instant.now();
        var b = new JcaX509v3CertificateBuilder(new X500Name("CN=atlas-test-ca"), BigInteger.TWO, Date.from(now.minusSeconds(60)),
                Date.from(now.plus(Duration.ofDays(1))), new X500Name("CN=" + cn), kp.getPublic());
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
        return new EdgeCredentials(Pem.privateKey(kp.getPrivate()), Pem.certificate(cert), Pem.certificate(ca));
    }
}
