package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyDiff;
import io.github.ggfto.atlas.core.TopologyPatch;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versoes do mapa para o SSE (design §3.4). A cada {@code atlas.stream.every} compara o mapa atual com o
 * anterior: mudou, ganha versao nova e um patch vai para quem esta conectado. As ultimas versoes ficam num anel
 * para quem reconecta com {@code Last-Event-ID}: so o que mudou desde la, ou um snapshot se ficou velho demais.
 */
@ApplicationScoped
public class TopologyStream {

    /** O que um cliente novo recebe primeiro. */
    public sealed interface Initial {
        record Snapshot(long version, Topology topology) implements Initial {
        }

        record Patch(TopologyPatch patch) implements Initial {
        }

        record UpToDate(long version) implements Initial {
        }
    }

    private final TopologyService topology;
    private final int history;
    private final BroadcastProcessor<TopologyPatch> live = BroadcastProcessor.create();
    private final Map<Long, Topology> ring = new LinkedHashMap<>();
    private long version;
    private Topology current;

    TopologyStream(TopologyService topology, AtlasConfig config) {
        this.topology = topology;
        this.history = config.stream().history();
    }

    @Scheduled(every = "{atlas.stream.every}")
    void tick() {
        refresh();
    }

    public synchronized void refresh() {
        Topology next = topology.snapshot();
        if (current == null) {
            version = 1;
            current = next;
            remember(version, next);
            return;
        }
        TopologyPatch p = TopologyDiff.between(current, next, version, version + 1);
        if (p.isEmpty()) {
            return;
        }
        version++;
        current = next;
        remember(version, next);
        live.onNext(p);
    }

    public synchronized Initial initial(String lastEventId) {
        if (current == null) {
            refresh();
        }
        Long last = parse(lastEventId);
        if (last != null && last == version) {
            return new Initial.UpToDate(version);
        }
        Topology base = last == null ? null : ring.get(last);
        if (base != null) {
            return new Initial.Patch(TopologyDiff.between(base, current, last, version));
        }
        return new Initial.Snapshot(version, current);
    }

    public Multi<TopologyPatch> live() {
        return live;
    }

    private void remember(long v, Topology t) {
        ring.put(v, t);
        while (ring.size() > history) {
            ring.remove(ring.keySet().iterator().next());
        }
    }

    private static Long parse(String id) {
        try {
            return id == null || id.isBlank() ? null : Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
