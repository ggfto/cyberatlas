package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Sanitizer;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyMerger;
import io.github.ggfto.atlas.core.TopologySource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Coleta as fontes e guarda o ultimo fragmento de cada uma. Fonte que falha mantem o fragmento anterior: as
 * observacoes dele vencem pelo TTL e o no vira unknown sozinho, sem o coletor inventar estado.
 */
public final class TopologyCollector {

    private static final Logger LOG = Logger.getLogger(TopologyCollector.class);

    private final List<TopologySource> sources;
    private final Map<String, Fragment> last = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public TopologyCollector(List<TopologySource> sources) {
        this.sources = List.copyOf(sources);
    }

    public void collect() {
        for (TopologySource s : sources) {
            try {
                last.put(s.name(), s.collect());
                errors.remove(s.name());
            } catch (Exception e) {
                errors.put(s.name(), String.valueOf(e.getMessage()));
                LOG.warnf("fonte %s falhou: %s", s.name(), e.getMessage());
            }
        }
    }

    /** Fragmento empurrado por um edge (ja passado pelo EdgeFragmentGuard). */
    public void accept(Fragment fragment) {
        last.put(fragment.source(), fragment);
    }

    /** Maquina declarada de um no (ex.: a de uma rota no services.yml). */
    public String machineOf(String nodeKey) {
        for (Fragment f : last.values()) {
            for (var n : f.nodes()) {
                if (n.key().equals(nodeKey)) {
                    return n.machine();
                }
            }
        }
        return null;
    }

    public Topology snapshot(Instant now) {
        return TopologyMerger.merge(List.copyOf(last.values()), Sanitizer.DEFAULT, now);
    }

    public List<String> sourceNames() {
        return sources.stream().map(TopologySource::name).toList();
    }

    public Map<String, String> lastErrors() {
        return Map.copyOf(errors);
    }
}
