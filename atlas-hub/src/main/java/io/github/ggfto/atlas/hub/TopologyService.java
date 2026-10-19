package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.adapters.hub.CheckTarget;
import io.github.ggfto.atlas.adapters.hub.HttpProber;
import io.github.ggfto.atlas.adapters.hub.PublicChecks;
import io.github.ggfto.atlas.adapters.hub.ServicesYmlSource;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologySource;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Monta as fontes a partir da config e coleta no boot e a cada {@code atlas.collect.every}. */
@ApplicationScoped
public class TopologyService {

    private final TopologyCollector collector;

    TopologyService(AtlasConfig config) {
        Clock clock = Clock.systemUTC();
        List<ServicesYmlSource> files = new ArrayList<>();
        new TreeMap<>(config.servicesyml().files()).forEach((machine, file) ->
                files.add(new ServicesYmlSource(machine, () -> read(Path.of(file)), clock)));
        List<TopologySource> sources = new ArrayList<>(files);
        if (config.checks().enabled()) {
            sources.add(new PublicChecks(() -> targets(files), new HttpProber(config.checks().timeout()), clock,
                    config.checks().ttl()));
        }
        this.collector = new TopologyCollector(sources);
    }

    void onStart(@Observes StartupEvent event) {
        collector.collect();
    }

    @Scheduled(every = "{atlas.collect.every}", delayed = "{atlas.collect.every}")
    void scheduled() {
        collector.collect();
    }

    public Topology snapshot() {
        return collector.snapshot(Instant.now());
    }

    private static List<CheckTarget> targets(List<ServicesYmlSource> files) {
        return files.stream().flatMap(f -> f.checkTargets().stream()).toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("services.yml ilegivel: " + file, e);
        }
    }
}
