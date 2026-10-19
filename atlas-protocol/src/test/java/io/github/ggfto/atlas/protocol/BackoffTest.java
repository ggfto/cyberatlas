package io.github.ggfto.atlas.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Reconexao com backoff exponencial e jitter de ate 20% (molde do WebSocketFrameChannel do hive-agent). */
class BackoffTest {

    @Test
    void doublesUpToTheCeilingWithoutJitter() {
        Backoff b = new Backoff(Duration.ofSeconds(1), Duration.ofSeconds(60), () -> 0.0);
        assertEquals(Duration.ofSeconds(1), b.delay(1));
        assertEquals(Duration.ofSeconds(2), b.delay(2));
        assertEquals(Duration.ofSeconds(32), b.delay(6));
        assertEquals(Duration.ofSeconds(60), b.delay(7));
        assertEquals(Duration.ofSeconds(60), b.delay(1000));
    }

    @Test
    void jitterAddsAtMostTwentyPercent() {
        Backoff b = new Backoff(Duration.ofSeconds(10), Duration.ofSeconds(60), () -> 0.999);
        assertTrue(b.delay(1).compareTo(Duration.ofMillis(11_998)) >= 0, String.valueOf(b.delay(1)));
        assertTrue(b.delay(1).compareTo(Duration.ofSeconds(12)) <= 0, String.valueOf(b.delay(1)));
    }
}
