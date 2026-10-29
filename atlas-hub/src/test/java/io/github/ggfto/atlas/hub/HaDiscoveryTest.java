package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.*;
import io.github.ggfto.atlas.core.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class HaDiscoveryTest {
    static TopologyNode node(NodeKind kind, String reason) {
        return new TopologyNode(new Node("route:ha.example", kind, "HA", null,
                Map.of("password", "never-export", "fqdn", "10.20.30.40")),
                new NodeStatus(State.ONLINE, reason, List.of()), List.of("password=driftsecret"));
    }

    @ParameterizedTest @EnumSource(NodeKind.class)
    void restrictsKindsAndDefinesThreeEntities(NodeKind kind) {
        var messages = HaDiscovery.messages(node(kind, "ok"));
        boolean allowed = Set.of(NodeKind.ROUTE, NodeKind.SERVICE, NodeKind.MACHINE,
                NodeKind.TUNNEL, NodeKind.ARGO).contains(kind);
        assertEquals(allowed ? 4 : 0, messages.size());
        if (!allowed) return;
        assertEquals(3, messages.stream().filter(m -> m.topic().endsWith("/config")).count());
        for (var message : messages) {
            assertTrue(message.retain());
            if (message.topic().endsWith("/config")) {
                assertTrue(message.payload().contains("atlas/status"));
                assertTrue(message.payload().contains("unique_id"));
                assertTrue(message.payload().contains("offline"));
            }
        }
    }

    @Test void masksAllFreeTextAndDropsPrivateAttributes() {
        var messages = HaDiscovery.messages(node(NodeKind.ROUTE,
                "10.20.30.40 password=supersecret eyJabcdefghijk.abcdefghijk.abcdefghijk"));
        String payload = messages.toString();
        for (String secret : List.of("10.20.30.40", "supersecret", "eyJabcdefghijk", "never-export", "driftsecret"))
            assertFalse(payload.contains(secret), secret);
        assertTrue(payload.contains("<ip>"));
        assertTrue(payload.contains("redacted"));
    }

    @Test void stableSafeCollisionResistantIds() {
        String a = HaDiscovery.objectId("route:a/b");
        assertTrue(a.matches("[a-z0-9_]+"));
        assertEquals(a, HaDiscovery.objectId("route:a/b"));
        assertNotEquals(a, HaDiscovery.objectId("route:a_b"));
        assertFalse(HaDiscovery.objectId("password=secret").contains("secret"));
    }

    @Test void removesOnlyRetainedDiscoveryConfigs() {
        var messages = HaDiscovery.remove("route:ha.example");
        assertEquals(3, messages.size());
        assertTrue(messages.stream().allMatch(m -> m.retain() && m.payload().isEmpty() && m.topic().endsWith("/config")));
    }
}
