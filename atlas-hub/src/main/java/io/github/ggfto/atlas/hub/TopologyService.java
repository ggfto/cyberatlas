package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.adapters.hub.ArgoSource;
import io.github.ggfto.atlas.adapters.hub.CheckTarget;
import io.github.ggfto.atlas.adapters.hub.CloudflareSource;
import io.github.ggfto.atlas.core.HttpProber;
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
    private final java.util.Map<String, ServicesYmlSource> byMachine = new java.util.TreeMap<>();

    TopologyService(AtlasConfig config) {
        Clock clock = Clock.systemUTC();
        List<ServicesYmlSource> files = new ArrayList<>();
        new TreeMap<>(config.servicesyml().files()).forEach((machine, file) -> {
            ServicesYmlSource src = new ServicesYmlSource(machine, () -> read(Path.of(file)), clock);
            files.add(src);
            byMachine.put(machine, src);
        });
        List<TopologySource> sources = new ArrayList<>(files);
        AtlasConfig.Cloudflare cf = config.cloudflare();
        if (cf.token().isPresent() && cf.accountId().isPresent()) {
            String zone = cf.zone().orElseGet(() -> files.isEmpty() ? "" : files.get(0).zone());
            java.util.Map<String, String> tunnels = new java.util.HashMap<>();
            byMachine.forEach((machine, src) -> src.tunnelId().ifPresent(id -> tunnels.put(id, machine)));
            sources.add(new CloudflareSource(java.net.URI.create(cf.api()), cf.token().get(), cf.accountId().get(), zone, tunnels,
                    java.time.Duration.ofSeconds(10), clock, config.checks().ttl()));
        }
        AtlasConfig.Argocd argo = config.argocd();
        if (argo.url().isPresent() && argo.token().isPresent()) {
            sources.add(new ArgoSource(java.net.URI.create(argo.url().get()), argo.token().get(), argo.clusterMachines(),
                    java.time.Duration.ofSeconds(10), clock, config.checks().ttl()));
        }
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

    /** Fragmento de um edge, ja filtrado pela guarda. */
    public void accept(io.github.ggfto.atlas.core.Fragment fragment) {
        collector.accept(fragment);
    }

    public String machineOf(String nodeKey) {
        return collector.machineOf(nodeKey);
    }

    /** Backends e rotas *.local da maquina: vao no Welcome do edge. */
    public List<CheckTarget> localTargets(String machine) {
        ServicesYmlSource src = byMachine.get(machine);
        return src == null ? List.of() : src.localTargets();
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
