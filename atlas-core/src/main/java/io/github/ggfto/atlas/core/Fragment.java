package io.github.ggfto.atlas.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** O que uma fonte devolve numa coleta: nos, arestas e observacoes de estado. Quem junta e o {@link TopologyMerger}. */
public record Fragment(String source, Instant observedAt, Duration ttl, List<Node> nodes, List<Edge> edges,
                       List<Observation> observations) {
    public Fragment {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        observations = List.copyOf(observations);
    }
}
