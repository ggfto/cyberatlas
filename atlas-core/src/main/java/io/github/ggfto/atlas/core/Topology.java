package io.github.ggfto.atlas.core;

import java.time.Instant;
import java.util.List;

/** O mapa inteiro como sai para o browser. */
public record Topology(Instant generatedAt, List<TopologyNode> nodes, List<Edge> edges) {
    public Topology {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
