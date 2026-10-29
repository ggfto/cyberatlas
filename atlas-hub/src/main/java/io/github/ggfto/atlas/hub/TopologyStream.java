package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyDiff;
import io.github.ggfto.atlas.core.TopologyPatch;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.smallrye.mutiny.subscription.Cancellable;
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

    /** So para teste: roda no {@link #follow(String)} entre o inicial e a inscricao no ao vivo, o ponto da corrida. */
    volatile Runnable afterInitial = () -> {
    };

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
        // sempre atualiza antes: o tick pode ter rodado antes da primeira coleta (ou estar a 5 s de rodar), e o
        // cliente novo tem de receber o mapa de agora; a mudanca, se houver, vira patch para os outros
        refresh();
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

    /**
     * O inicial (nada, se o cliente ja esta em dia) e depois cada patch ao vivo, sem buraco entre os dois. O inicial,
     * a inscricao no ao vivo e a emissao do inicial acontecem sob o mesmo lock do {@link #refresh()}, que e quem emite
     * os patches: nenhum patch posterior ao inicial sai antes de o cliente estar inscrito. Montar
     * {@code concat(inicial, live())} fora daqui perde o patch que cair entre o inicial ir para o fio e a inscricao
     * (corrida vista na CI).
     */
    public Multi<Initial> follow(String lastEventId) {
        return Multi.createFrom().emitter(em -> {
            synchronized (this) {
                Initial first = initial(lastEventId);
                afterInitial.run();
                Cancellable patches = live.subscribe().with(p -> em.emit(new Initial.Patch(p)));
                em.onTermination(patches::cancel);
                if (!(first instanceof Initial.UpToDate)) {
                    em.emit(first);
                }
            }
        });
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
