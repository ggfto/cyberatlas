package io.github.ggfto.atlas.core;

import java.util.List;

/** Estado agregado de um no e as fontes que o compuseram. */
public record NodeStatus(State state, String reason, List<SourceState> sources) {
    public NodeStatus {
        sources = List.copyOf(sources);
    }
}
