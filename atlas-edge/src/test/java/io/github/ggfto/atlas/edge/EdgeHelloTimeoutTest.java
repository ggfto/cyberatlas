package io.github.ggfto.atlas.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.protocol.EdgeCredentials;
import io.github.ggfto.atlas.protocol.Frame;
import io.github.ggfto.atlas.protocol.FrameCodec;
import io.github.ggfto.atlas.protocol.Pem;
import io.github.ggfto.atlas.protocol.ProtocolVersion;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

/**
 * O prazo do Hello chega ao edge real pela configuracao: o EdgeAgent sobe com credencial salva e
 * {@code atlas.edge.hello-timeout=1s} contra um hub falso (TLS) que engole o Hello da primeira sessao; o edge tem de
 * desistir dela e mandar o Hello de novo numa segunda sessao, muito antes do que qualquer outro caminho reconectaria.
 */
@QuarkusTest
@TestProfile(EdgeHelloTimeoutTest.Profile.class)
class EdgeHelloTimeoutTest {

    @Test
    void configuredHelloTimeoutAbandonsTheMuteSessionAndHelloesAgain() throws Exception {
        for (int i = 0; i < 100 && FakeHub.hellos.get() < 2; i++) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        assertEquals(2, FakeHub.hellos.get(), "sem segundo Hello: o prazo configurado nao chegou ao EdgeLink");
        assertEquals(2, FakeHub.sessions.get());
        long ms = TimeUnit.NANOSECONDS.toMillis(FakeHub.secondOpen - FakeHub.firstOpen);
        assertTrue(ms >= 1000, "abandonou a sessao antes do prazo de 1 s: " + ms + " ms");
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("atlas.edge.hello-timeout", "1s");
        }

        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(FakeHub.class));
        }
    }

    /** Hub falso com TLS: engole o Hello da sessao 1 e responde Welcome nas seguintes. */
    public static class FakeHub implements QuarkusTestResourceLifecycleManager {
        static final AtomicInteger sessions = new AtomicInteger();
        static final AtomicInteger hellos = new AtomicInteger();
        static volatile long firstOpen;
        static volatile long secondOpen;
        private Vertx vertx;
        private Path state;

        @Override
        public Map<String, String> start() {
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
                gen.initialize(256);
                KeyPair ca = gen.generateKeyPair();
                KeyPair server = gen.generateKeyPair();
                KeyPair edge = gen.generateKeyPair();
                X509Certificate caCert = issue("CN=atlas-test-ca", ca.getPublic(), "CN=atlas-test-ca", ca.getPrivate(), true, null);
                X509Certificate serverCert = issue("CN=localhost", server.getPublic(), "CN=atlas-test-ca", ca.getPrivate(), false, "localhost");
                X509Certificate edgeCert = issue("CN=edge-lab", edge.getPublic(), "CN=atlas-test-ca", ca.getPrivate(), false, null);
                state = Files.createTempDirectory("atlas-edge-hello");
                new CredentialStore(state).save(new EdgeCredentials(Pem.privateKey(edge.getPrivate()), Pem.certificate(edgeCert),
                        Pem.certificate(caCert)));

                vertx = Vertx.vertx();
                HttpServerOptions tls = new HttpServerOptions().setSsl(true).setKeyCertOptions(new PemKeyCertOptions()
                        .setCertValue(Buffer.buffer(Pem.certificate(serverCert))).setKeyValue(Buffer.buffer(Pem.privateKey(server.getPrivate()))));
                // upgrade pelo requestHandler: so com webSocketHandler o Vert.x as vezes trata o upgrade como request
                // comum e cai sem handler (NPE no event loop, o edge fica pendurado ate o connect timeout)
                HttpServer http = vertx.createHttpServer(tls).requestHandler(req -> req.toWebSocket().onSuccess(ws -> {
                    int session = sessions.incrementAndGet();
                    if (session == 1) {
                        firstOpen = System.nanoTime();
                    } else if (session == 2) {
                        secondOpen = System.nanoTime();
                    }
                    ws.textMessageHandler(text -> {
                        if (FrameCodec.decode(text) instanceof Frame.Hello) {
                            hellos.incrementAndGet();
                            if (session > 1) {
                                ws.writeTextMessage(FrameCodec.encode(
                                        new Frame.Welcome("s" + session, ProtocolVersion.CURRENT, 60_000, 90_000, List.of())));
                            }
                        }
                    });
                })).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                return Map.of("atlas.edge.machine", "lab", "atlas.edge.hub", "https://localhost:" + http.actualPort(),
                        "atlas.edge.state-dir", state.toString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void stop() {
            // o Quarkus chama o stop() tambem quando o teste falha: a pasta com a chave de teste do edge nao fica no /tmp
            try {
                if (vertx != null) {
                    vertx.close().toCompletionStage().toCompletableFuture().join();
                }
            } finally {
                deleteTree(state);
            }
        }

        static void deleteTree(Path dir) {
            if (dir == null || !Files.exists(dir)) {
                return;
            }
            try (var all = Files.walk(dir)) {
                all.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private static X509Certificate issue(String subject, PublicKey key, String issuer, PrivateKey signer, boolean ca, String dns)
                throws Exception {
            Instant now = Instant.now();
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(System.nanoTime()),
                    Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(1))), new X500Name(subject), key);
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
            if (dns != null) {
                b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, dns)));
            }
            return new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer)));
        }
    }
}
