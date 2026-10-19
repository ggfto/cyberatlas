package io.github.ggfto.atlas.protocol;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Espera entre reconexoes: dobra a cada tentativa ate o teto, mais ate 20% de jitter (evita reconexao em manada). */
public final class Backoff {

    private final Duration base;
    private final Duration max;
    private final DoubleSupplier random;

    public Backoff(Duration base, Duration max, DoubleSupplier random) {
        this.base = base;
        this.max = max;
        this.random = random;
    }

    public Duration delay(int attempt) {
        int exp = Math.min(Math.max(attempt, 1) - 1, 30);
        long ms = Math.min(max.toMillis(), base.toMillis() << exp);
        return Duration.ofMillis(ms + (long) (ms * 0.2 * random.getAsDouble()));
    }
}
