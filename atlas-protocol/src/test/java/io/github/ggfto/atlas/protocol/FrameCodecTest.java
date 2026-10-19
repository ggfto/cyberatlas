package io.github.ggfto.atlas.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void everyFrameRoundTrips() {
        Fragment frag = new Fragment("docker", AT, Duration.ofSeconds(90),
                List.of(new Node("docker:vps/9router", NodeKind.CONTAINER, "9router", "vps", Map.of("image", "ghcr.io/x/y:1"))),
                List.of(Edge.of("docker:vps/9router", "machine:vps", EdgeKind.RUNS_ON)),
                List.of(new Observation("docker:vps/9router", "docker", State.ONLINE, "running", AT, Duration.ofSeconds(90))));
        List<Frame> frames = List.of(
                new Frame.Hello("edge-vps", 1, "vps", List.of("docker", "kubernetes"), Map.of("docker", "1")),
                new Frame.Welcome("s-1", 1, 30_000, 90_000, List.of(
                        new Frame.LocalCheck("service:vps/9router", "http://9router:20128/", "auth.gf2.in", false),
                        new Frame.LocalCheck("route:fx.local.gf2.in", "https://fx.local.gf2.in/", "auth.gf2.in", true))),
                new Frame.Reject("versao 7 fora da janela [0, 1]"),
                new Frame.Snapshot("docker", 3, frag),
                new Frame.Resync("docker"),
                new Frame.Heartbeat(AT.toEpochMilli()));
        for (Frame f : frames) {
            assertEquals(f, FrameCodec.decode(FrameCodec.encode(f)));
        }
    }

    @Test
    void wireFormatCarriesTheTypeAndIsoTimes() {
        String json = FrameCodec.encode(new Frame.Snapshot("docker", 1,
                new Fragment("docker", AT, Duration.ofSeconds(90), List.of(), List.of(), List.of())));
        assertTrue(json.contains("\"type\":\"snapshot\""), json);
        assertTrue(json.contains("\"2026-10-19T12:00:00Z\""), json);
        assertTrue(json.contains("\"PT1M30S\""), json);
    }

    @Test
    void unknownOrBrokenFrameIsAProtocolError() {
        assertThrows(ProtocolException.class, () -> FrameCodec.decode("{\"type\":\"exec\",\"cmd\":\"rm -rf /\"}"));
        assertThrows(ProtocolException.class, () -> FrameCodec.decode("nao e json"));
        assertThrows(ProtocolException.class, () -> FrameCodec.decode("{\"edgeId\":\"x\"}"));
    }

    @Test
    void oversizedFrameIsRefusedBeforeParsing() {
        String big = "{\"type\":\"heartbeat\",\"epochMs\":1,\"pad\":\"" + "x".repeat(FrameCodec.MAX_FRAME_CHARS) + "\"}";
        ProtocolException e = assertThrows(ProtocolException.class, () -> FrameCodec.decode(big));
        assertTrue(e.getMessage().startsWith("frame grande demais"), e.getMessage());
    }
}
