package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regras de estado do design §3.3: pior entre as fontes frescas; sem fonte fresca = unknown, nunca "online velho". */
class StatusMergeTest {

    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    static final Duration TTL = Duration.ofSeconds(90);

    static Observation obs(String source, State state, String reason, long secondsAgo) {
        return new Observation("route:x.gf2.in", source, state, reason, NOW.minusSeconds(secondsAgo), TTL);
    }

    @Test
    void worstFreshSourceWins() {
        NodeStatus st = StatusMerge.merge(List.of(
                obs("check:publico", State.ONLINE, "ok", 10),
                obs("traefik:vps", State.DEGRADED, "backend lento", 5)), NOW);
        assertEquals(State.DEGRADED, st.state());
        assertEquals("backend lento", st.reason());
    }

    @Test
    void offlineBeatsDegraded() {
        NodeStatus st = StatusMerge.merge(List.of(
                obs("a", State.DEGRADED, "lento", 1), obs("b", State.OFFLINE, "502", 1), obs("c", State.ONLINE, "ok", 1)), NOW);
        assertEquals(State.OFFLINE, st.state());
        assertEquals("502", st.reason());
    }

    @Test
    void expiredSourceDoesNotCount() {
        NodeStatus st = StatusMerge.merge(List.of(
                obs("check:publico", State.ONLINE, "ok", 10),
                obs("edge:host", State.OFFLINE, "container parado", 600)), NOW);
        assertEquals(State.ONLINE, st.state());
    }

    @Test
    void onlyExpiredSourcesIsUnknownNeverStaleOnline() {
        NodeStatus st = StatusMerge.merge(List.of(obs("edge:host", State.ONLINE, "ok", 180)), NOW);
        assertEquals(State.UNKNOWN, st.state());
        assertEquals("nenhuma fonte fresca (ultima: edge:host ha 180s)", st.reason());
    }

    @Test
    void edgeDownIsUnknownNotOffline() {
        // a ultima coisa que o edge disse foi "offline", mas o edge sumiu: nao sabemos, nao e offline
        NodeStatus st = StatusMerge.merge(List.of(obs("edge:host", State.OFFLINE, "container parado", 300)), NOW);
        assertEquals(State.UNKNOWN, st.state());
    }

    @Test
    void lastExpiredMentionedIsTheMostRecent() {
        NodeStatus st = StatusMerge.merge(List.of(obs("velha", State.ONLINE, "ok", 900), obs("edge:vps", State.ONLINE, "ok", 120)), NOW);
        assertEquals("nenhuma fonte fresca (ultima: edge:vps ha 120s)", st.reason());
    }

    @Test
    void noObservationIsUnknown() {
        NodeStatus st = StatusMerge.merge(List.of(), NOW);
        assertEquals(State.UNKNOWN, st.state());
        assertEquals("sem fonte", st.reason());
    }

    @Test
    void freshUnknownDoesNotOverrideAnAnswer() {
        NodeStatus st = StatusMerge.merge(List.of(obs("a", State.ONLINE, "ok", 1), obs("b", State.UNKNOWN, "sem dado", 1)), NOW);
        assertEquals(State.ONLINE, st.state());
    }

    @Test
    void onlyFreshUnknownKeepsItsReason() {
        NodeStatus st = StatusMerge.merge(List.of(obs("b", State.UNKNOWN, "check sem expectativa", 1)), NOW);
        assertEquals(State.UNKNOWN, st.state());
        assertEquals("check sem expectativa", st.reason());
    }

    @Test
    void freshnessBoundaryIsExclusive() {
        assertTrue(obs("a", State.ONLINE, "ok", 89).freshAt(NOW));
        assertFalse(obs("a", State.ONLINE, "ok", 90).freshAt(NOW));
    }

    @Test
    void sourcesListEveryObservationWithItsFreshness() {
        NodeStatus st = StatusMerge.merge(List.of(obs("a", State.ONLINE, "ok", 1), obs("b", State.OFFLINE, "x", 500)), NOW);
        assertEquals(List.of(
                new SourceState("a", State.ONLINE, "ok", NOW.minusSeconds(1), true),
                new SourceState("b", State.OFFLINE, "x", NOW.minusSeconds(500), false)), st.sources());
    }

    @Test
    void tieBetweenEquallyBadSourcesIsDeterministic() {
        NodeStatus st = StatusMerge.merge(List.of(obs("z", State.OFFLINE, "z caiu", 1), obs("a", State.OFFLINE, "a caiu", 1)), NOW);
        assertEquals("a caiu", st.reason());
    }
}
