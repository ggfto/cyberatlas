package io.github.ggfto.atlas.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Junta fragmentos de varias fontes num mapa: nos pela chave (atributos somados; em conflito vale a fonte de
 * nome menor, para ser deterministico), arestas por id, so as que ligam nos existentes, e o estado de cada no
 * pelo {@link StatusMerge}. Todo no sai pelo {@link Sanitizer}.
 */
public final class TopologyMerger {

    private TopologyMerger() {
    }

    public static Topology merge(List<Fragment> fragments, Sanitizer sanitizer, Instant now) {
        List<Fragment> ordered = fragments.stream().sorted(Comparator.comparing(Fragment::source)).toList();
        Map<String, Node> nodes = new TreeMap<>();
        Map<String, List<Observation>> observations = new LinkedHashMap<>();
        for (Fragment f : ordered) {
            for (Node n : f.nodes()) {
                nodes.merge(n.key(), n, TopologyMerger::combine);
            }
            for (Observation o : f.observations()) {
                observations.computeIfAbsent(o.nodeKey(), k -> new ArrayList<>()).add(o);
            }
        }
        List<TopologyNode> out = nodes.values().stream()
                .map(n -> new TopologyNode(sanitizer.sanitize(n),
                        StatusMerge.merge(observations.getOrDefault(n.key(), List.of()), now)))
                .toList();
        Map<String, Edge> edges = new TreeMap<>();
        for (Fragment f : ordered) {
            for (Edge e : f.edges()) {
                if (nodes.containsKey(e.from()) && nodes.containsKey(e.to())) {
                    edges.putIfAbsent(e.id(), e);
                }
            }
        }
        return new Topology(now, out, List.copyOf(edges.values()));
    }

    private static Node combine(Node first, Node later) {
        Map<String, String> attrs = new LinkedHashMap<>(later.attributes());
        attrs.putAll(first.attributes());
        return new Node(first.key(), first.kind(), first.name(), first.machine() != null ? first.machine() : later.machine(), attrs);
    }
}
