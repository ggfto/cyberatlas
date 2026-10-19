package io.github.ggfto.atlas.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Diferenca entre duas versoes do mapa, por chave de no e id de aresta (o patch do SSE, design §3.4). */
public final class TopologyDiff {

    private TopologyDiff() {
    }

    public static TopologyPatch between(Topology from, Topology to, long base, long version) {
        Map<String, TopologyNode> before = byKey(from.nodes());
        Map<String, TopologyNode> after = byKey(to.nodes());
        List<TopologyNode> upsertNodes = new ArrayList<>();
        after.forEach((k, n) -> {
            if (!Objects.equals(before.get(k), n)) {
                upsertNodes.add(n);
            }
        });
        List<String> removeNodes = before.keySet().stream().filter(k -> !after.containsKey(k)).toList();
        Map<String, Edge> eb = edges(from.edges());
        Map<String, Edge> ea = edges(to.edges());
        List<Edge> upsertEdges = ea.values().stream().filter(e -> !e.equals(eb.get(e.id()))).toList();
        List<String> removeEdges = eb.keySet().stream().filter(id -> !ea.containsKey(id)).toList();
        return new TopologyPatch(version, base, to.generatedAt(), upsertNodes, removeNodes, upsertEdges, removeEdges);
    }

    public static Topology apply(Topology base, TopologyPatch p) {
        Map<String, TopologyNode> nodes = new TreeMap<>(byKey(base.nodes()));
        p.removeNodes().forEach(nodes::remove);
        p.upsertNodes().forEach(n -> nodes.put(n.node().key(), n));
        Map<String, Edge> edges = new TreeMap<>(edges(base.edges()));
        p.removeEdges().forEach(edges::remove);
        p.upsertEdges().forEach(e -> edges.put(e.id(), e));
        return new Topology(p.generatedAt(), List.copyOf(nodes.values()), List.copyOf(edges.values()));
    }

    private static Map<String, TopologyNode> byKey(List<TopologyNode> nodes) {
        Map<String, TopologyNode> m = new LinkedHashMap<>();
        nodes.forEach(n -> m.put(n.node().key(), n));
        return m;
    }

    private static Map<String, Edge> edges(List<Edge> edges) {
        Map<String, Edge> m = new LinkedHashMap<>();
        edges.forEach(e -> m.put(e.id(), e));
        return m;
    }
}
