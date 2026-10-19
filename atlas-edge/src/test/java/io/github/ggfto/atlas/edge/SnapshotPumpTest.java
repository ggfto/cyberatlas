package io.github.ggfto.atlas.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.TopologySource;
import io.github.ggfto.atlas.protocol.Frame;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotPumpTest {

    static TopologySource src(String name, boolean fails) {
        return new TopologySource() {
            public String name() {
                return name;
            }

            public Fragment collect() {
                if (fails) {
                    throw new IllegalStateException("docker.sock sumiu");
                }
                return new Fragment(name, Instant.now(), Duration.ofSeconds(90), List.of(), List.of(), List.of());
            }
        };
    }

    @Test
    void everySourceIsSentEvenIfOneFails() {
        List<Frame.Snapshot> sent = new ArrayList<>();
        SnapshotPump pump = new SnapshotPump(() -> List.of(src("docker", true), src("traefik", false), src("kubernetes", false)),
                f -> sent.add((Frame.Snapshot) f));
        assertEquals(2, pump.pump());
        assertEquals(List.of("traefik", "kubernetes"), sent.stream().map(Frame.Snapshot::source).toList());
        pump.pump();
        assertEquals(List.of(1L, 2L, 3L, 4L), sent.stream().map(Frame.Snapshot::seq).toList());
    }

    @Test
    void nothingCountsAsSentWhenTheLinkIsDown() {
        SnapshotPump pump = new SnapshotPump(() -> List.of(src("docker", false)), f -> false);
        assertEquals(0, pump.pump());
    }
}
