package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopologyMergerTest {

    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    static Fragment frag(String source, List<Node> nodes, List<Edge> edges, List<Observation> obs) {
        return new Fragment(source, NOW.minusSeconds(5), Duration.ofSeconds(90), nodes, edges, obs);
    }

    static Node node(String key, NodeKind kind, Map<String, String> attrs) {
        return new Node(key, kind, key, "vps", attrs);
    }

    @Test
    void joinsNodesByKeyAcrossSourcesAndAttachesMergedStatus() {
        Fragment a = frag("servicesyml:vps", List.of(node("route:ha.gf2.in", NodeKind.ROUTE, Map.of("fqdn", "ha.gf2.in"))), List.of(), List.of());
        Fragment b = frag("check:publico", List.of(node("route:ha.gf2.in", NodeKind.ROUTE, Map.of("auth", "false"))), List.of(),
                List.of(new Observation("route:ha.gf2.in", "check:publico", State.OFFLINE, "502", NOW.minusSeconds(3), Duration.ofSeconds(90))));
        Topology t = TopologyMerger.merge(List.of(b, a), Sanitizer.DEFAULT, NOW);
        assertEquals(1, t.nodes().size());
        TopologyNode n = t.nodes().get(0);
        assertEquals(Map.of("fqdn", "ha.gf2.in", "auth", "false"), n.node().attributes());
        assertEquals(State.OFFLINE, n.status().state());
    }

    @Test
    void nodeWithoutObservationIsUnknown() {
        Topology t = TopologyMerger.merge(List.of(frag("s", List.of(node("traefik:vps", NodeKind.TRAEFIK, Map.of())), List.of(), List.of())), Sanitizer.DEFAULT, NOW);
        assertEquals(State.UNKNOWN, t.nodes().get(0).status().state());
    }

    @Test
    void duplicateEdgesCollapseAndDanglingEdgesAreDropped() {
        Edge e = new Edge("e1", "traefik:vps", "route:ha.gf2.in", EdgeKind.ROUTES_TO);
        Fragment f = frag("s", List.of(node("traefik:vps", NodeKind.TRAEFIK, Map.of()), node("route:ha.gf2.in", NodeKind.ROUTE, Map.of())),
                List.of(e, e, new Edge("e2", "route:ha.gf2.in", "service:vps/sumiu", EdgeKind.ROUTES_TO)), List.of());
        assertEquals(List.of(e), TopologyMerger.merge(List.of(f), Sanitizer.DEFAULT, NOW).edges());
    }

    @Test
    void everyNodeLeavesSanitizedAndInStableOrder() {
        Fragment f = frag("s", List.of(
                node("service:vps/b", NodeKind.SERVICE, Map.of("backend", "http://b:1", "tunnel_id", "x")),
                node("service:vps/a", NodeKind.SERVICE, Map.of("backend", "http://a:1"))), List.of(), List.of());
        Topology t = TopologyMerger.merge(List.of(f), Sanitizer.DEFAULT, NOW);
        assertEquals(List.of("service:vps/a", "service:vps/b"), t.nodes().stream().map(n -> n.node().key()).toList());
        assertEquals(Map.of("backend", "http://b:1"), t.nodes().get(1).node().attributes());
        assertEquals(NOW, t.generatedAt());
    }

    @Test
    void firstSourceByNameWinsOnAttributeConflict() {
        Fragment a = frag("a", List.of(node("route:x", NodeKind.ROUTE, Map.of("exposure", "local"))), List.of(), List.of());
        Fragment b = frag("b", List.of(node("route:x", NodeKind.ROUTE, Map.of("exposure", "externo"))), List.of(), List.of());
        assertEquals("local", TopologyMerger.merge(List.of(b, a), Sanitizer.DEFAULT, NOW).nodes().get(0).node().attributes().get("exposure"));
    }

    @Test
    void correlatedEdgesJoinTheMap() {
        Fragment yml = frag("servicesyml:vps", List.of(new Node("service:vps/9router", NodeKind.SERVICE, "9router", "vps",
                Map.of("backend", "http://9router:20128"))), List.of(), List.of());
        Fragment edge = frag("edge:vps/docker", List.of(new Node("docker:vps/9router", NodeKind.CONTAINER, "9router", "vps", Map.of())),
                List.of(), List.of());
        assertEquals(List.of(Edge.of("service:vps/9router", "docker:vps/9router", EdgeKind.ROUTES_TO)),
                TopologyMerger.merge(List.of(yml, edge), Sanitizer.DEFAULT, NOW).edges());
    }
}
