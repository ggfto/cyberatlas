package io.github.ggfto.atlas.adapters.edge;

import io.github.ggfto.atlas.core.Expectation;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.HttpProber;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.TopologySource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;

/**
 * Checks que so o edge alcanca (design §1.4): o backend interno de cada servico (o status do upstream, que o
 * check publico atras do Authentik nao prova) e as rotas {@code *.local}. Mesmas regras do hub, fonte check:local.
 */
public final class LocalChecks implements TopologySource {

    public record Target(String nodeKey, URI url, Expectation expectation) {
    }

    /** Tradutor de host da rodada; se a listagem do Docker falhar, o alvo vai como veio. */
    public interface Resolver {
        UnaryOperator<URI> snapshot() throws Exception;
    }

    private final Supplier<List<Target>> targets;
    private final Resolver resolver;
    private final HttpProber prober;
    private final Clock clock;
    private final Duration ttl;

    public LocalChecks(Supplier<List<Target>> targets, Duration timeout, Clock clock, Duration ttl) {
        this(targets, () -> UnaryOperator.identity(), timeout, clock, ttl);
    }

    public LocalChecks(Supplier<List<Target>> targets, Resolver resolver, Duration timeout, Clock clock, Duration ttl) {
        this.targets = targets;
        this.resolver = resolver;
        this.prober = new HttpProber(timeout);
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public String name() {
        return Expectation.LOCAL;
    }

    @Override
    public Fragment collect() throws Exception {
        List<Target> all = targets.get();
        for (Target t : all) {
            String scheme = t.url().getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("check local so http/https: " + t.url());
            }
        }
        UnaryOperator<URI> resolve;
        try {
            resolve = resolver.snapshot();
        } catch (Exception e) {
            resolve = UnaryOperator.identity();
        }
        UnaryOperator<URI> r = resolve;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = all.stream().map(t -> pool.submit(() -> t.expectation()
                    .classify(t.nodeKey(), prober.probe(r.apply(t.url())), clock.instant(), ttl, Expectation.LOCAL))).toList();
            List<Observation> obs = new ArrayList<>();
            for (var f : futures) {
                obs.add(f.get());
            }
            return new Fragment(name(), clock.instant(), ttl, List.of(), List.of(), obs);
        }
    }
}
