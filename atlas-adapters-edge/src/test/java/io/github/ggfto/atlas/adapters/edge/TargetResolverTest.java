package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ggfto.atlas.core.Expectation;
import io.github.ggfto.atlas.core.Observation;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edge num pod do k3s: sem DNS do Docker e sem host-gateway. O alvo do check vira IP como o compose resolvia,
 * so com a listagem {@code /containers/json} (nunca o inspect).
 */
class TargetResolverTest {

    static final String GATEWAY = "172.17.0.1";
    static final String NODE = "192.168.68.107";

    /** Recorte da listagem do host (nomes do snapshot de producao; IPs de exemplo nas bridges). */
    static final String LIST = """
            [
              {"Names": ["/fx-panel"], "State": "running", "Labels": {"com.docker.compose.service": "panel"},
               "HostConfig": {"NetworkMode": "fx-panel_default"},
               "NetworkSettings": {"Networks": {"fx-panel_default": {"IPAddress": "172.19.0.4"}, "traefik": {"IPAddress": "172.23.0.9"}}}},
              {"Names": ["/fivem"], "State": "running", "Labels": {"com.docker.compose.service": "fivem"},
               "NetworkSettings": {"Networks": {"fx-panel_default": {"IPAddress": "172.19.0.3"}}}},
              {"Names": ["/traefik"], "State": "running", "Labels": {"com.docker.compose.service": "traefik"},
               "NetworkSettings": {"Networks": {"traefik": {"IPAddress": "172.23.0.2"}}}},
              {"Names": ["/homelable-lab-frontend-1"], "Labels": {"com.docker.compose.service": "frontend"},
               "NetworkSettings": {"Networks": {"homelable": {"IPAddress": "172.28.0.2"}}}},
              {"Names": ["/registronic-frontend-1"], "Labels": {"com.docker.compose.service": "frontend"},
               "NetworkSettings": {"Networks": {"registronic": {"IPAddress": "172.29.0.2"}}}},
              {"Names": ["/parado"], "State": "exited", "NetworkSettings": {"Networks": {"x": {"IPAddress": ""}}}},
              {"Names": ["/pihole"], "HostConfig": {"NetworkMode": "host"}, "NetworkSettings": {"Networks": {"host": {"IPAddress": ""}}}}
            ]""";

    static Map<String, String> ips() throws Exception {
        return TargetResolver.containerIps(new ObjectMapper().readTree(LIST), NODE);
    }

    static String resolve(String url) throws Exception {
        return TargetResolver.resolve(URI.create(url), ips(), GATEWAY, NODE).toString();
    }

    @Test
    void nomeDeContainerViraOIpDaBridge() throws Exception {
        assertEquals("http://172.19.0.4:8080/", resolve("http://fx-panel:8080/"));
        assertEquals("http://172.19.0.3:40120/", resolve("http://fivem:40120/"));
        assertEquals("http://172.23.0.2:8080/api/http/routers", resolve("http://traefik:8080/api/http/routers"));
        assertEquals("http://172.19.0.4:8080/", resolve("http://FX-Panel:8080/"), "DNS nao diferencia caixa");
    }

    @Test
    void servicoComposeSoQuandoUnico() throws Exception {
        assertEquals("http://172.19.0.4:8080/", resolve("http://panel:8080/"));
        assertEquals("http://frontend:80/", resolve("http://frontend:80/"), "dois projetos com 'frontend': nao chuta");
    }

    @Test
    void aliasesDoHost() throws Exception {
        assertEquals("http://172.17.0.1:9119/", resolve("http://host.docker.internal:9119/"), "hermes so escuta no docker0");
        assertEquals("http://172.17.0.1:30129/", resolve("http://host.docker.internal:30129/"));
        assertEquals("http://172.17.0.1:5000/", resolve("http://172.17.0.1:5000/"), "o gateway ja e alcancavel do pod");
        assertEquals("http://192.168.68.107:5000/", resolve("http://localhost:5000/"));
        assertEquals("http://192.168.68.107:5000/", resolve("http://127.0.0.1:5000/"));
        assertEquals("http://localhost:5000/", TargetResolver.resolve(URI.create("http://localhost:5000/"), Map.of(), GATEWAY, null).toString(),
                "sem IP do no, loopback fica como esta");
    }

    @Test
    void redeHostViraOIpDoNo() throws Exception {
        assertEquals("http://192.168.68.107:80/admin", resolve("http://pihole:80/admin"));
    }

    @Test
    void desconhecidoParadoEFqdnFicamComoEstao() throws Exception {
        assertEquals("http://dozzle-dozzle-1:8080/", resolve("http://dozzle-dozzle-1:8080/"));
        assertEquals("http://parado:80/", resolve("http://parado:80/"));
        assertEquals("https://fx.local.gf2.in/", resolve("https://fx.local.gf2.in/"));
    }

    @Test
    void caminhoQueryEUserInfoSeguemCrus() throws Exception {
        assertEquals("http://u@172.19.0.4:8080/a%20b/c?x=1%2F2#f", resolve("http://u@fx-panel:8080/a%20b/c?x=1%2F2#f"));
        assertEquals("http://172.19.0.4/", resolve("http://fx-panel/"));
    }

    @Test
    void snapshotUsaSoAListagemPermitida(@TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of("/containers/json", new FakeDockerSocket.Reply(200, LIST, false)))) {
            UnaryOperator<URI> r = new TargetResolver(new DockerClient(fake.socket, Duration.ofSeconds(5)), GATEWAY, NODE).snapshot();
            assertEquals(URI.create("http://172.19.0.3:40120/"), r.apply(URI.create("http://fivem:40120/")));
            assertEquals(List.of("/containers/json"), fake.requested);
            assertTrue(fake.requested.stream().allMatch(DockerClient::allowed));
        }
    }

    @Test
    void checkLocalTraduzOAlvoEDistingueNaoResolve() throws Exception {
        try (FakeHttp f = new FakeHttp(Map.of("/", new FakeHttp.Reply(200, "ok")))) {
            int port = f.base().getPort();
            Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
            Map<String, String> fakeIps = Map.of("fx-panel", "127.0.0.1");
            LocalChecks checks = new LocalChecks(() -> List.of(
                    new LocalChecks.Target("service:host/fx-panel", URI.create("http://fx-panel:" + port + "/"), new Expectation.Open("auth.gf2.in")),
                    new LocalChecks.Target("service:host/dozzle", URI.create("http://dozzle-dozzle-1.invalid:8080/"), new Expectation.Open("auth.gf2.in"))),
                    () -> u -> TargetResolver.resolve(u, fakeIps, GATEWAY, NODE), Duration.ofSeconds(5), clock, Duration.ofSeconds(90));
            Map<String, String> reason = checks.collect().observations().stream().collect(Collectors.toMap(Observation::nodeKey, Observation::reason));
            assertEquals("sem resposta: nao resolve", reason.get("service:host/dozzle"));
            assertTrue(!reason.get("service:host/fx-panel").startsWith("sem resposta"), reason.get("service:host/fx-panel"));
        }
    }

    @Test
    void listagemDoDockerFalhandoOAlvoVaiComoVeio() throws Exception {
        try (FakeHttp f = new FakeHttp(Map.of("/", new FakeHttp.Reply(200, "ok")))) {
            LocalChecks checks = new LocalChecks(() -> List.of(new LocalChecks.Target("service:host/x", f.base().resolve("/"),
                    new Expectation.Open("auth.gf2.in"))), () -> {
                        throw new java.io.IOException("docker fora");
                    }, Duration.ofSeconds(5), Clock.systemUTC(), Duration.ofSeconds(90));
            assertEquals(1, checks.collect().observations().size());
        }
    }
}
