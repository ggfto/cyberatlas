package io.github.ggfto.atlas.adapters.hub;

import io.github.ggfto.atlas.core.Expectation;
import io.github.ggfto.atlas.core.HttpProber;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.TopologySource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Checa as rotas publicas pela internet, em paralelo (virtual threads). So observa: os nos vem do services.yml. */
public final class PublicChecks implements TopologySource {

    private final Supplier<List<CheckTarget>> targets;
    private final HttpProber prober;
    private final Clock clock;
    private final Duration ttl;

    public PublicChecks(Supplier<List<CheckTarget>> targets, HttpProber prober, Clock clock, Duration ttl) {
        this.targets = targets;
        this.prober = prober;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public String name() {
        return Expectation.PUBLIC;
    }

    @Override
    public Fragment collect() throws Exception {
        List<CheckTarget> all = targets.get();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = all.stream().map(t -> pool.submit(() -> t.expectation()
                    .classify(t.nodeKey(), prober.probe(t.url()), clock.instant(), ttl))).toList();
            List<Observation> obs = new java.util.ArrayList<>();
            for (var f : futures) {
                obs.add(f.get());
            }
            return new Fragment(name(), clock.instant(), ttl, List.of(), List.of(), obs);
        }
    }
}
