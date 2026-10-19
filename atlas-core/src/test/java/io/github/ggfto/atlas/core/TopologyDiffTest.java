package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Patch do stream (design §3.4): so o que mudou entre duas versoes do mapa, por chave. */
class TopologyDiffTest {

    static final Instant T0 = Instant.parse("2026-10-19T12:00:00Z");

    static TopologyNode n(String key, State st) {
        return new TopologyNode(new Node(key, NodeKind.SERVICE, key, "vps", Map.of()), new NodeStatus(st, st.name(), List.of()), List.of());
    }

    static Topology t(Instant at, List<TopologyNode> nodes, List<Edge> edges) {
        return new Topology(at, nodes, edges);
    }

    @Test
    void addedChangedAndRemovedNodesAndEdges() {
        Edge e1 = Edge.of("a", "b", EdgeKind.ROUTES_TO);
        Edge e2 = Edge.of("a", "c", EdgeKind.ROUTES_TO);
        Topology a = t(T0, List.of(n("a", State.ONLINE), n("b", State.ONLINE), n("velho", State.ONLINE)), List.of(e1));
        Topology b = t(T0.plusSeconds(5), List.of(n("a", State.ONLINE), n("b", State.OFFLINE), n("c", State.ONLINE)), List.of(e2));
        TopologyPatch p = TopologyDiff.between(a, b, 7, 8);
        assertEquals(8, p.version());
        assertEquals(7, p.base());
        assertEquals(List.of("b", "c"), p.upsertNodes().stream().map(x -> x.node().key()).toList());
        assertEquals(List.of("velho"), p.removeNodes());
        assertEquals(List.of(e2), p.upsertEdges());
        assertEquals(List.of(e1.id()), p.removeEdges());
        assertEquals(T0.plusSeconds(5), p.generatedAt());
    }

    @Test
    void sameMapIsAnEmptyPatchEvenIfTheClockMoved() {
        Topology a = t(T0, List.of(n("a", State.ONLINE)), List.of());
        Topology b = t(T0.plusSeconds(30), List.of(n("a", State.ONLINE)), List.of());
        assertTrue(TopologyDiff.between(a, b, 1, 2).isEmpty());
    }

    @Test
    void applyingThePatchGivesTheNewMap() {
        Edge e1 = Edge.of("a", "b", EdgeKind.ROUTES_TO);
        Topology a = t(T0, List.of(n("a", State.ONLINE), n("b", State.ONLINE)), List.of(e1));
        Topology b = t(T0.plusSeconds(5), List.of(n("a", State.DEGRADED), n("c", State.ONLINE)), List.of());
        Topology applied = TopologyDiff.apply(a, TopologyDiff.between(a, b, 1, 2));
        assertEquals(b.nodes(), applied.nodes());
        assertEquals(b.edges(), applied.edges());
    }
}
