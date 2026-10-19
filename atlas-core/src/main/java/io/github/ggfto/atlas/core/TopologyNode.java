package io.github.ggfto.atlas.core;

import java.util.List;

/** No ja sanitizado com o estado agregado e os selos de drift (design §3.3). */
public record TopologyNode(Node node, NodeStatus status, List<String> drift) {
    public TopologyNode {
        drift = drift == null ? List.of() : List.copyOf(drift);
    }
}
