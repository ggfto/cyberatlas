package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologySource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TopologyCollectorTest {

    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    static TopologySource source(String name, String key, AtomicInteger calls, int failFrom) {
        return new TopologySource() {
            public String name() {
                return name;
            }

            public Fragment collect() {
                if (calls.incrementAndGet() >= failFrom) {
                    throw new IllegalStateException("api fora");
                }
                return new Fragment(name, NOW, Duration.ofSeconds(90),
                        List.of(new Node(key, NodeKind.SERVICE, key, "vps", Map.of())), List.of(), List.of());
            }
        };
    }

    @Test
    void failingSourceKeepsItsLastFragmentAndDoesNotStopTheOthers() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        TopologyCollector c = new TopologyCollector(List.of(source("a", "service:vps/a", a, 2), source("b", "service:vps/b", b, 1)));
        c.collect();
        c.collect();
        Topology t = c.snapshot(NOW);
        assertEquals(List.of("service:vps/a"), t.nodes().stream().map(n -> n.node().key()).toList());
        assertEquals(2, a.get());
        assertEquals(2, b.get());
        assertEquals(Map.of("a", "api fora", "b", "api fora"), c.lastErrors());
    }
}
