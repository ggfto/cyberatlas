package io.github.ggfto.atlas.hub.spike;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.certs.CertificateGenerator;
import io.smallrye.certs.CertificateRequest;
import io.smallrye.certs.Format;
import io.smallrye.certs.PemCertificateFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** HTTP + HTTPS no mesmo app, com certificado gerado na hora (nenhuma chave no repo). */
public class BreakGlassPortProfile implements QuarkusTestProfile {

    static final int TLS_TEST_PORT = 8444; // quarkus.http.test-ssl-port padrao

    @Override
    public Map<String, String> getConfigOverrides() {
        try {
            Path dir = Files.createTempDirectory("atlas-spike-tls");
            PemCertificateFiles pem = (PemCertificateFiles) new CertificateGenerator(dir, true).generate(new CertificateRequest()
                    .withName("spike").withCN("localhost").withFormat(Format.PEM).withDuration(Duration.ofDays(1))).get(0);
            return Map.of(
                    "quarkus.http.ssl.certificate.files", pem.certFile().toString(),
                    "quarkus.http.ssl.certificate.key-files", pem.keyFile().toString(),
                    "quarkus.http.insecure-requests", "enabled",
                    "spike.breakglass-port", String.valueOf(TLS_TEST_PORT),
                    "quarkus.http.auth.permission.breakglass.paths", "/spike/breakglass/*,/spike/breakglass",
                    "quarkus.http.auth.permission.breakglass.policy", "breakglass-port");
        } catch (Exception e) {
            throw new IllegalStateException("certificado do spike", e);
        }
    }
}
