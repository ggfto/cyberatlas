package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.configuration.DurationConverter;
import io.quarkus.tls.runtime.config.PemKeyCertConfig;
import io.quarkus.tls.runtime.config.TlsBucketConfig;
import io.quarkus.tls.runtime.config.TlsConfig;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Porta TLS de producao (8443: WS mTLS dos edges, enrollment, break-glass). Em producao o hub so tem o
 * application.properties e o env do deploy/kustomize/base/deployment.yaml; os testes Quarkus passam o TLS como
 * propriedade (EdgePkiProfile), que mapeia sempre, e por isso nao pegaram que uma variavel de ambiente com chave de
 * mapa dinamica (QUARKUS_TLS_KEY_STORE_PEM_0_CERT, o "0" de pem.0) nao vira propriedade se a chave nao estiver
 * declarada em alguma fonte: sem key store, o Quarkus sobe so a 8080, calado.
 *
 * O teste monta o SmallRye Config como no runtime (env + application.properties, perfil prod, o mapeamento do
 * quarkus.tls), com o env lido do proprio deployment.yaml, e exige que o key store resolva para a PKI do /pki.
 * Variavel de ambiente nao se injeta num @QuarkusTest; subir o jar com env e o IT manual da correcao.
 */
class ProdTlsConfigTest {

    static final Path DEPLOYMENT = Path.of("../deploy/kustomize/base/deployment.yaml");
    static final Pattern ENV = Pattern.compile("\\{name: ([A-Z0-9_]+), value: \"?([^\"}]*?)\"?\\}");

    static Map<String, String> deploymentEnv() throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        Matcher m = ENV.matcher(Files.readString(DEPLOYMENT));
        while (m.find()) {
            env.put(m.group(1), m.group(2).trim());
        }
        assertTrue(env.containsKey("QUARKUS_HTTP_SSL_PORT"), "env do deployment nao lido: " + env);
        return env;
    }

    static SmallRyeConfig prodConfig() throws Exception {
        return new SmallRyeConfigBuilder()
                .withProfile("prod")
                .addDefaultInterceptors()
                .withConverter(java.time.Duration.class, 200, new DurationConverter())
                .withSources(new EnvConfigSource(deploymentEnv(), 300))
                .withSources(new PropertiesConfigSource(
                        ProdTlsConfigTest.class.getClassLoader().getResource("application.properties"), 250))
                .withMapping(TlsConfig.class)
                .withValidateUnknown(false)
                .build();
    }

    @Test
    void prodServesTheTlsPortWithThePkiFromSlashPki() throws Exception {
        SmallRyeConfig config = prodConfig();
        assertEquals(8443, config.getValue("quarkus.http.ssl-port", Integer.class));
        assertEquals(config.getValue("quarkus.http.ssl-port", Integer.class),
                config.getValue("atlas.edges.port", Integer.class), "a porta de edge e a porta TLS");

        TlsBucketConfig tls = config.getConfigMapping(TlsConfig.class).defaultCertificateConfig()
                .orElseThrow(() -> new AssertionError("sem quarkus.tls default: o hub nao abre a 8443"));
        PemKeyCertConfig pem = tls.keyStore().flatMap(k -> k.pem())
                .orElseThrow(() -> new AssertionError("sem key store PEM: o hub nao abre a 8443"));
        PemKeyCertConfig.KeyCertConfig server = pem.keyCerts().values().stream().findFirst()
                .orElseThrow(() -> new AssertionError("key store PEM sem par cert/chave"));
        assertEquals(Path.of("/pki/server.crt"), server.cert());
        assertEquals(Path.of("/pki/server.key"), server.key());
        assertEquals(java.util.List.of(Path.of("/pki/ca.crt")),
                tls.trustStore().orElseThrow().pem().orElseThrow().certs().orElseThrow());
    }
}
