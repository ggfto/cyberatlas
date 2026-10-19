package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Validade do certificado da rota (design §1.4): online com cert vencendo em menos de 14 dias e degradado. */
class CertificateExpiryTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        "-  | ONLINE   | 200",
        "90 | ONLINE   | 200",
        "14 | ONLINE   | 200",
        "13 | DEGRADED | cert vence em 13 dias",
        "0  | DEGRADED | cert vence em 0 dias",
    })
    void onlineRouteWithCertCloseToExpiryIsDegraded(Integer days, State state, String reason) {
        Observation o = new Expectation.Open("auth.gf2.in").classify("route:x", new ProbeResult(200, null, null, days), AT,
                Duration.ofSeconds(90));
        assertEquals(state, o.state());
        assertEquals(reason, o.reason());
    }

    @Test
    void certNeverHidesAWorseState() {
        Observation o = new Expectation.Open("auth.gf2.in").classify("route:x", new ProbeResult(503, null, null, 3), AT,
                Duration.ofSeconds(90));
        assertEquals(State.OFFLINE, o.state());
        assertEquals("erro 503", o.reason());
    }

    @Test
    void proberReadsTheDaysLeftOfTheServerCertificate() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        KeyPair kp = g.generateKeyPair();
        Instant now = Instant.now();
        var b = new JcaX509v3CertificateBuilder(new X500Name("CN=localhost"), BigInteger.ONE, Date.from(now.minusSeconds(60)),
                Date.from(now.plus(Duration.ofDays(10)).plusSeconds(3600)), new X500Name("CN=localhost"), kp.getPublic());
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(kp.getPrivate())));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, new char[0]);
        ks.setKeyEntry("s", kp.getPrivate(), new char[0], new X509Certificate[] {cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        KeyStore ts = KeyStore.getInstance("PKCS12");
        ts.load(null, new char[0]);
        ts.setCertificateEntry("s", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(server));
        https.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        https.start();
        try {
            ProbeResult r = new HttpProber(Duration.ofSeconds(5), client).probe(URI.create("https://127.0.0.1:" + https.getAddress().getPort() + "/"));
            assertEquals(200, r.status());
            assertEquals(10, r.certDaysLeft());
        } finally {
            https.stop(0);
        }
    }
}
