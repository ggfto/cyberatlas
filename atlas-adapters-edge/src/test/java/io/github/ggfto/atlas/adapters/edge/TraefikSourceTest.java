package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Traefik da propria maquina (design §1.2): o serverStatus vira o estado do upstream na rota. */
class TraefikSourceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    static String fixture(String name) throws IOException {
        try (InputStream in = TraefikSourceTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static FakeHttp fake() throws IOException {
        return new FakeHttp(Map.of(
                "/api/http/routers", new FakeHttp.Reply(200, fixture("traefik-routers.json")),
                "/api/http/services", new FakeHttp.Reply(200, fixture("traefik-services.json"))));
    }

    static Fragment collect(FakeHttp f) throws Exception {
        return new TraefikSource("vps", f.base(), Duration.ofSeconds(5), CLOCK, Duration.ofSeconds(90)).collect();
    }

    @Test
    void serverStatusBecomesTheUpstreamStateOfEachHost() throws Exception {
        try (FakeHttp f = fake()) {
            Map<String, Observation> obs = collect(f).observations().stream()
                    .collect(Collectors.toMap(Observation::nodeKey, o -> o));
            assertEquals(State.ONLINE, obs.get("route:logs.gf2.in").state());
            assertEquals("upstream: 1/1 servidores UP", obs.get("route:logs.gf2.in").reason());
            assertEquals(State.DEGRADED, obs.get("route:ha.gf2.in").state());
            assertEquals("upstream: 1/2 servidores UP", obs.get("route:ha.gf2.in").reason());
            assertEquals(State.DEGRADED, obs.get("route:ha2.gf2.in").state());
            assertEquals(State.DEGRADED, obs.get("route:quebrado.gf2.in").state());
            assertEquals("router com erro: the service \"quebrado@file\" does not exist", obs.get("route:quebrado.gf2.in").reason());
            assertEquals("traefik", obs.get("route:logs.gf2.in").source());
        }
    }

    @Test
    void withoutHealthcheckThereIsNoOpinion() throws Exception {
        try (FakeHttp f = fake()) {
            assertFalse(collect(f).observations().stream().anyMatch(o -> o.nodeKey().equals("route:semhc.gf2.in")));
        }
    }

    @Test
    void routersAreNodesUnderTheirTraefikAndInternalOnesAreSkipped() throws Exception {
        try (FakeHttp f = fake()) {
            Fragment frag = collect(f);
            List<String> keys = frag.nodes().stream().map(n -> n.key()).sorted().toList();
            assertEquals(List.of("router:vps/dozzle@file", "router:vps/ha@file", "router:vps/quebrado@file", "router:vps/semhc@file",
                    "traefik:vps"), keys);
            assertEquals(true, frag.edges().contains(Edge.of("traefik:vps", "router:vps/dozzle@file", EdgeKind.ROUTES_TO)));
        }
    }

    @Test
    void middlewaresAreNeverRead() throws Exception {
        // a config dos middlewares traz hashes de basicAuth e enderecos de forwardAuth
        try (FakeHttp f = fake()) {
            collect(f);
            assertEquals(List.of("/api/http/routers", "/api/http/services"), f.requested.stream().sorted().toList());
        }
    }

    @Test
    void apiErrorFailsTheCollect() throws Exception {
        try (FakeHttp f = new FakeHttp(Map.of())) {
            IOException e = assertThrows(IOException.class, () -> collect(f));
            assertEquals("traefik GET /api/http/routers: HTTP 500", e.getMessage());
        }
    }

    @Test
    void apiDoTraefikPorNomeDeContainerPassaPeloTradutor() throws Exception {
        try (FakeHttp f = fake()) {
            URI byName = URI.create("http://traefik:" + f.base().getPort() + "/");
            Fragment frag = new TraefikSource("vps", byName, () -> u -> TargetResolver.resolve(u, Map.of("traefik", "127.0.0.1"), null, null),
                    Duration.ofSeconds(5), CLOCK, Duration.ofSeconds(90)).collect();
            assertEquals(State.ONLINE, frag.observations().stream().filter(o -> o.nodeKey().equals("route:logs.gf2.in")).findFirst().orElseThrow().state());
        }
    }
}
