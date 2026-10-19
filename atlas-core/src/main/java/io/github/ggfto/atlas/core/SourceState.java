package io.github.ggfto.atlas.core;

import java.time.Instant;

/** Uma fonte na gaveta de detalhe do no: o que disse, quando, e se ainda conta. */
public record SourceState(String source, State state, String reason, Instant observedAt, boolean fresh) {
}
