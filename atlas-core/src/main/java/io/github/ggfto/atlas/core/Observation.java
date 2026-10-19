package io.github.ggfto.atlas.core;

import java.time.Duration;
import java.time.Instant;

/** O que uma fonte disse sobre um no, quando e por quanto tempo isso vale. */
public record Observation(String nodeKey, String source, State state, String reason, Instant observedAt, Duration ttl) {

    /** Fresca enquanto {@code now < observedAt + ttl}: no limite exato ja venceu. */
    public boolean freshAt(Instant now) {
        return now.isBefore(observedAt.plus(ttl));
    }
}
