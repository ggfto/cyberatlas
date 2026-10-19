package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ggfto.atlas.core.Expectation;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Checks que so o edge alcanca: backend interno e rota *.local, com as regras do hub e fonte check:local. */
class LocalChecksTest {

    @Test
    void backendAndLocalRouteAreJudgedWithTheSameRules() throws Exception {
        try (FakeHttp f = new FakeHttp(Map.of(
                "/backend", new FakeHttp.Reply(200, "ok"),
                "/backend-caido", new FakeHttp.Reply(502, ""),
                "/local-protegida", new FakeHttp.Reply(302, "", Map.of("Location", "https://auth.gf2.in/flow")),
                "/local-aberta-demais", new FakeHttp.Reply(200, "painel")))) {
            Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
            LocalChecks checks = new LocalChecks(() -> List.of(
                    new LocalChecks.Target("service:host/fx-panel", f.base().resolve("/backend"), new Expectation.Open("auth.gf2.in")),
                    new LocalChecks.Target("service:host/txadmin", f.base().resolve("/backend-caido"), new Expectation.Open("auth.gf2.in")),
                    new LocalChecks.Target("route:fx.local.gf2.in", f.base().resolve("/local-protegida"), new Expectation.Protected("auth.gf2.in")),
                    new LocalChecks.Target("route:tx.local.gf2.in", f.base().resolve("/local-aberta-demais"), new Expectation.Protected("auth.gf2.in"))),
                    Duration.ofSeconds(2), clock, Duration.ofSeconds(90));
            Fragment frag = checks.collect();
            Map<String, State> st = frag.observations().stream().collect(Collectors.toMap(Observation::nodeKey, Observation::state));
            assertEquals(Map.of("service:host/fx-panel", State.ONLINE, "service:host/txadmin", State.OFFLINE,
                    "route:fx.local.gf2.in", State.ONLINE, "route:tx.local.gf2.in", State.DEGRADED), st);
            assertEquals(List.of("check:local"), frag.observations().stream().map(Observation::source).distinct().toList());
            assertEquals("check:local", checks.name());
        }
    }

    @Test
    void nonHttpUrlIsNeverRequested() {
        LocalChecks.Target t = new LocalChecks.Target("service:host/x", java.net.URI.create("file:///etc/passwd"),
                new Expectation.Open("auth.gf2.in"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new LocalChecks(() -> List.of(t), Duration.ofSeconds(1), Clock.systemUTC(), Duration.ofSeconds(90)).collect());
    }
}
