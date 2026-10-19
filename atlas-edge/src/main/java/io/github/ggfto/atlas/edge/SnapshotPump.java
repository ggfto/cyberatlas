package io.github.ggfto.atlas.edge;

import io.github.ggfto.atlas.core.TopologySource;
import io.github.ggfto.atlas.protocol.Frame;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/** Uma rodada de coleta: cada fonte local vira um Snapshot; fonte que falha nao impede as outras. */
public final class SnapshotPump {

    private static final Logger LOG = Logger.getLogger(SnapshotPump.class);

    private final Supplier<List<TopologySource>> sources;
    private final Predicate<Frame> sender;
    private final AtomicLong seq = new AtomicLong();

    public SnapshotPump(Supplier<List<TopologySource>> sources, Predicate<Frame> sender) {
        this.sources = sources;
        this.sender = sender;
    }

    /** Quantos snapshots chegaram ao link nesta rodada. */
    public int pump() {
        int sent = 0;
        for (TopologySource s : sources.get()) {
            try {
                var fragment = s.collect();
                if (sender.test(new Frame.Snapshot(s.name(), seq.incrementAndGet(), fragment))) {
                    sent++;
                }
            } catch (Exception e) {
                LOG.warnf("fonte %s falhou: %s", s.name(), e.getMessage());
            }
        }
        return sent;
    }
}
