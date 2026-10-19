package io.github.ggfto.atlas.adapters.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PublicChecksTest {

    @Test
    void probesEveryTargetAndOnlyObserves() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            ex.getResponseHeaders().add("Location", "https://auth.gf2.in/flow");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/aberta", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Expectation protegida = new Expectation.Protected("auth.gf2.in");
            Clock clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
            PublicChecks checks = new PublicChecks(() -> List.of(
                    new CheckTarget("route:a", URI.create(base + "/ok"), protegida),
                    new CheckTarget("route:b", URI.create(base + "/aberta"), protegida)),
                    new HttpProber(Duration.ofSeconds(2)), clock, Duration.ofSeconds(90));
            Fragment f = checks.collect();
            assertEquals(List.of(), f.nodes());
            Map<String, State> st = f.observations().stream().collect(Collectors.toMap(Observation::nodeKey, Observation::state));
            assertEquals(Map.of("route:a", State.ONLINE, "route:b", State.DEGRADED), st);
            assertEquals(Duration.ofSeconds(90), f.observations().get(0).ttl());
        } finally {
            server.stop(0);
        }
    }
}
