package io.github.ggfto.atlas.adapters.hub;

import io.github.ggfto.atlas.core.Expectation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** services.yml -> nos de rota/servico/entrada (design §1.3), sobre os fixtures sanitizados do infra. */
class ServicesYmlSourceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    static String fixture(String name) {
        try (InputStream in = ServicesYmlSourceTest.class.getResourceAsStream("/fixtures/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Fragment collect(String machine, String yaml) {
        return new ServicesYmlSource(machine, () -> yaml, CLOCK).collect();
    }

    static Map<String, Node> byKey(Fragment f) {
        return f.nodes().stream().collect(Collectors.toMap(Node::key, n -> n));
    }

    @Test
    void localExposureBecomesLocalSubdomainBehindTheLan() {
        Fragment f = collect("host", fixture("services-host.yml"));
        Map<String, Node> n = byKey(f);
        assertEquals(Map.of("fqdn", "fx.local.gf2.in", "exposure", "local", "auth", "true", "url", "https://fx.local.gf2.in/"),
                n.get("route:fx.local.gf2.in").attributes());
        assertTrue(f.edges().contains(Edge.of("entry:host/lan", "traefik:host", EdgeKind.ROUTES_TO)));
        assertTrue(f.edges().contains(Edge.of("traefik:host", "route:fx.local.gf2.in", EdgeKind.ROUTES_TO)));
        assertEquals(NodeKind.ENTRY, n.get("entry:host/lan").kind());
    }

    @Test
    void externalExposureUsesSubdomainExternoAndTheTunnel() {
        Fragment f = collect("host", fixture("services-host.yml"));
        Map<String, Node> n = byKey(f);
        // portainer do host: subdomain "docker" e so base; o nome publico vem de subdomain_externo
        Node svc = n.get("service:host/portainer");
        String publico = f.edges().stream().filter(e -> e.to().equals(svc.key()) && e.kind() == EdgeKind.ROUTES_TO)
                .map(Edge::from).findFirst().orElseThrow();
        assertEquals("route:docker-lab.gf2.in", publico);
        assertFalse(n.containsKey("route:docker.gf2.in"), "docker.gf2.in e outro servico, nao o portainer do host");
        assertTrue(f.edges().contains(Edge.of("entry:host/tunel", "traefik:host", EdgeKind.ROUTES_TO)));
    }

    @Test
    void authTrueGoesThroughTheAuthGateAndAuthFalseDoesNot() {
        Fragment f = collect("vps", fixture("services-vps.yml"));
        Set<Edge> edges = Set.copyOf(f.edges());
        assertTrue(edges.contains(Edge.of("route:logs.gf2.in", "authgate:authentik", EdgeKind.AUTH_VIA)));
        assertFalse(edges.contains(Edge.of("route:ha.gf2.in", "authgate:authentik", EdgeKind.AUTH_VIA)));
        assertTrue(edges.contains(Edge.of("route:ha.gf2.in", "service:vps/homeassistant", EdgeKind.ROUTES_TO)));
        assertEquals(Map.of("backend", "http://homeassistant:8123", "exposure", "externo", "auth", "false"),
                byKey(f).get("service:vps/homeassistant").attributes());
    }

    @Test
    void ambosCreatesBothNamesAndDiretoSkipsTheTunnel() {
        String yaml = """
                zone: exemplo.test
                services:
                  painel:
                    subdomain: painel
                    backend: http://painel:80
                    exposure: ambos
                    auth: true
                  mqtt:
                    subdomain: mqtt
                    backend: http://mqtt:1883
                    exposure: direto
                    auth: false
                """;
        Fragment f = collect("vps", yaml);
        Map<String, Node> n = byKey(f);
        assertTrue(n.containsKey("route:painel.local.exemplo.test"));
        assertTrue(n.containsKey("route:painel.exemplo.test"));
        assertTrue(f.edges().contains(Edge.of("entry:vps/direto", "traefik:vps", EdgeKind.ROUTES_TO)));
        assertTrue(f.edges().contains(Edge.of("traefik:vps", "route:mqtt.exemplo.test", EdgeKind.ROUTES_TO)));
    }

    @Test
    void secretsOfTheFileNeverBecomeNodeData() {
        for (String m : new String[] {"host", "vps"}) {
            String all = collect(m, fixture("services-" + m + ".yml")).nodes().toString();
            for (String leak : new String[] {"192.0.2.10", "198.51.100.20", "00000000-0000-0000-0000-00000000", "tunnel_id",
                    "lan_ip", "public_ip", "managed_by", "auth_bypass_paths"}) {
                assertFalse(all.contains(leak), m + " vazou " + leak);
            }
        }
    }

    @Test
    void declaredOnlyNoStateIsInvented() {
        assertEquals(0, collect("vps", fixture("services-vps.yml")).observations().size());
    }

    @Test
    void publicRoutesBecomeCheckTargetsWithTheRightExpectation() {
        ServicesYmlSource src = new ServicesYmlSource("vps", () -> fixture("services-vps.yml"), CLOCK);
        Map<String, Expectation> targets = src.checkTargets().stream()
                .collect(Collectors.toMap(CheckTarget::nodeKey, CheckTarget::expectation));
        assertEquals(new Expectation.Protected("auth.gf2.in"), targets.get("route:logs.gf2.in"));
        assertEquals(new Expectation.Open("auth.gf2.in"), targets.get("route:ha.gf2.in"));
        // rota local nao e checada da internet: quem checa e o edge da maquina (F2)
        ServicesYmlSource host = new ServicesYmlSource("host", () -> fixture("services-host.yml"), CLOCK);
        assertFalse(host.checkTargets().stream().anyMatch(t -> t.nodeKey().contains(".local.")));
    }

    @Test
    void unknownExposureFailsClosed() {
        String yaml = "zone: x.test\nservices:\n  a:\n    subdomain: a\n    backend: http://a:1\n    exposure: publico\n    auth: false\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> collect("vps", yaml));
        assertEquals("services.yml (vps): exposure invalida em a: publico", e.getMessage());
    }

    @Test
    void localTargetsAreTheBackendsAndTheLocalRoutesOfTheMachine() {
        ServicesYmlSource host = new ServicesYmlSource("host", () -> fixture("services-host.yml"), CLOCK);
        Map<String, CheckTarget> t = host.localTargets().stream().collect(Collectors.toMap(CheckTarget::nodeKey, x -> x));
        // backend interno: o status do upstream que o check publico atras do Authentik nao prova
        assertEquals("http://fx-panel:8080/", t.get("service:host/fx-panel").url().toString());
        assertEquals(new Expectation.Open("auth.gf2.in"), t.get("service:host/fx-panel").expectation());
        // rota *.local: so a LAN (e o edge) alcanca
        assertEquals(new Expectation.Protected("auth.gf2.in"), t.get("route:fx.local.gf2.in").expectation());
        assertFalse(t.keySet().stream().anyMatch(k -> k.startsWith("route:") && !k.contains(".local.")));
    }

    @Test
    void optionalIconOverridesArePreservedWithoutChangingAbsentAttributes() {
        String yaml = """
                zone: exemplo.test
                services:
                  painel:
                    subdomain: painel
                    backend: http://painel:80
                    exposure: externo
                    icon: ' redis '
                  simples:
                    subdomain: simples
                    backend: http://simples:80
                    exposure: local
                  vazio:
                    subdomain: vazio
                    backend: http://vazio:80
                    exposure: local
                    icon: '   '
                """;
        Map<String, Node> nodes = byKey(collect("host", yaml));
        assertEquals(Map.of("backend", "http://painel:80", "exposure", "externo", "auth", "false", "icon", "redis"),
                nodes.get("service:host/painel").attributes());
        assertEquals(Map.of("backend", "http://simples:80", "exposure", "local", "auth", "false"),
                nodes.get("service:host/simples").attributes());
        assertFalse(nodes.get("service:host/vazio").attributes().containsKey("icon"));
    }

    @Test
    void tunnelIdIsKnownInsideButNeverANodeAttribute() {
        ServicesYmlSource vps = new ServicesYmlSource("vps", () -> fixture("services-vps.yml"), CLOCK);
        assertEquals(java.util.Optional.of("00000000-0000-0000-0000-00000000bbbb"), vps.tunnelId());
        assertEquals(java.util.Optional.empty(), new ServicesYmlSource("x", () -> "zone: a.test\nservices: {}\n", CLOCK).tunnelId());
    }
}
