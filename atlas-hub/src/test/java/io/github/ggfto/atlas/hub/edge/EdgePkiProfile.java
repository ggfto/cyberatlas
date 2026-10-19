package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.github.ggfto.atlas.protocol.Csr;
import io.github.ggfto.atlas.protocol.Pem;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hub com a porta de edge (8444 em teste) em TLS, cert de servidor e CA do atlas gerados na hora (nenhuma chave
 * no repo), mTLS opcional no handshake (quem exige o cert e o upgrade do /edge) e o admin de edges ligado.
 */
public class EdgePkiProfile implements QuarkusTestProfile {

    static final int EDGE_PORT = 8444;
    static Path dir;

    @Override
    public Map<String, String> getConfigOverrides() {
        try {
            dir = Files.createTempDirectory("atlas-edge-pki");
            AtlasCa ca = AtlasCa.generate("atlas-test-ca", Duration.ofDays(1));
            KeyPair server = Csr.newKeyPair();
            Files.writeString(dir.resolve("ca.crt"), ca.certificatePem());
            Pem.writePrivateKey(dir.resolve("ca.key"), java.security.KeyFactory.getInstance("EC")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Pem.readPrivateKey(ca.privateKeyPem()).getEncoded())));
            Files.writeString(dir.resolve("server.crt"), Pem.certificate(ca.issueServer(server.getPublic(), List.of("localhost"),
                    List.of("127.0.0.1"), Duration.ofDays(1))));
            Pem.writePrivateKey(dir.resolve("server.key"), server.getPrivate());
            Map<String, String> c = new HashMap<>(new FixtureProfile().getConfigOverrides());
            c.put("quarkus.tls.key-store.pem.0.cert", dir.resolve("server.crt").toString());
            c.put("quarkus.tls.key-store.pem.0.key", dir.resolve("server.key").toString());
            c.put("quarkus.tls.trust-store.pem.certs", dir.resolve("ca.crt").toString());
            c.put("quarkus.http.ssl.client-auth", "request");
            c.put("quarkus.http.insecure-requests", "enabled");
            c.put("atlas.edges.ca-cert", dir.resolve("ca.crt").toString());
            c.put("atlas.edges.ca-key", dir.resolve("ca.key").toString());
            c.put("atlas.edges.port", String.valueOf(EDGE_PORT));
            c.put("atlas.edges.admin-enabled", "true");
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("PKI de teste", e);
        }
    }

    static String caPem() throws java.io.IOException {
        return Files.readString(dir.resolve("ca.crt"));
    }
}
