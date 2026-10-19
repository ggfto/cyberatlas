package io.github.ggfto.atlas.core;

import java.time.Instant;
import java.util.List;

/** O que mudou da versao {@code base} para {@code version}: nos/arestas novos ou alterados e chaves removidas. */
public record TopologyPatch(long version, long base, Instant generatedAt, List<TopologyNode> upsertNodes,
                            List<String> removeNodes, List<Edge> upsertEdges, List<String> removeEdges) {
    public TopologyPatch {
        upsertNodes = List.copyOf(upsertNodes);
        removeNodes = List.copyOf(removeNodes);
        upsertEdges = List.copyOf(upsertEdges);
        removeEdges = List.copyOf(removeEdges);
    }

    public boolean isEmpty() {
        return upsertNodes.isEmpty() && removeNodes.isEmpty() && upsertEdges.isEmpty() && removeEdges.isEmpty();
    }
}
