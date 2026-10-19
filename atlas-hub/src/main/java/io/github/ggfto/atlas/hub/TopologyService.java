package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.adapters.hub.ArgoSource;
import io.github.ggfto.atlas.adapters.hub.CheckTarget;
import io.github.ggfto.atlas.adapters.hub.CloudflareSource;
import io.github.ggfto.atlas.core.HttpProber;
import io.github.ggfto.atlas.adapters.hub.PublicChecks;
import io.github.ggfto.atlas.adapters.hub.GitHubFile;
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

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(TopologyService.class);

    private final TopologyCollector collector;
    private final java.util.Map<String, ServicesYmlSource> byMachine = new java.util.TreeMap<>();

    TopologyService(AtlasConfig config) {
        Clock clock = Clock.systemUTC();
        AtlasConfig.ServicesYml.Github gh = config.servicesyml().github();
        List<ServicesYmlSource> files = new ArrayList<>();
        servicesYml(config.servicesyml().files(), gh.paths(), gh.repo(),
                (repo, path) -> new GitHubFile(java.net.URI.create(gh.api()), repo, path, gh.ref(), gh.token(), gh.timeout(), clock),
                file -> () -> read(Path.of(file))).forEach((machine, yaml) -> {
                    ServicesYmlSource src = new ServicesYmlSource(machine, yaml, clock);
                    files.add(src);
                    byMachine.put(machine, src);
                });
        List<TopologySource> sources = new ArrayList<>(files);
        AtlasConfig.Cloudflare cf = config.cloudflare();
        if (cf.token().isPresent() && cf.accountId().isPresent()) {
            // zona e tuneis saem do services.yml a cada coleta: GitHub fora no boot nao derruba o hub
            java.util.function.Supplier<String> zone = () -> cf.zone().orElseGet(() -> files.isEmpty() ? "" : files.get(0).zone());
            java.util.function.Supplier<java.util.Map<String, String>> tunnels = () -> {
                java.util.Map<String, String> t = new java.util.HashMap<>();
                byMachine.forEach((machine, src) -> src.tunnelId().ifPresent(id -> t.put(id, machine)));
                return t;
            };
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

    /** Backends e rotas *.local da maquina: vao no Welcome do edge. services.yml ilegivel = nenhum (o erro ja aparece na fonte). */
    public List<CheckTarget> localTargets(String machine) {
        ServicesYmlSource src = byMachine.get(machine);
        if (src == null) {
            return List.of();
        }
        try {
            return src.localTargets();
        } catch (RuntimeException e) {
            LOG.warnf("checks locais de %s indisponiveis: %s", machine, e.getMessage());
            return List.of();
        }
    }

    /** Cada fonte coletada pelo hub com o ultimo erro (null = a ultima coleta deu certo). */
    public List<SourceStatus> sources() {
        java.util.Map<String, String> errors = collector.lastErrors();
        return collector.sourceNames().stream().map(n -> new SourceStatus(n, errors.get(n))).toList();
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public record SourceStatus(String name, String error) {
    }

    /**
     * services.yml por maquina: arquivo local ({@code files}) ou GitHub ({@code githubPaths}). A mesma maquina nos
     * dois, ou paths sem repo, e erro de config e para o boot (melhor que um mapa silenciosamente de outra fonte).
     */
    static java.util.Map<String, java.util.function.Supplier<String>> servicesYml(java.util.Map<String, String> files,
            java.util.Map<String, String> githubPaths, java.util.Optional<String> repo,
            java.util.function.BiFunction<String, String, java.util.function.Supplier<String>> github,
            java.util.function.Function<String, java.util.function.Supplier<String>> file) {
        java.util.Map<String, java.util.function.Supplier<String>> out = new TreeMap<>();
        files.forEach((machine, f) -> out.put(machine, file.apply(f)));
        if (!githubPaths.isEmpty() && repo.filter(r -> !r.isBlank()).isEmpty()) {
            throw new IllegalStateException("atlas.servicesyml.github.paths sem atlas.servicesyml.github.repo");
        }
        githubPaths.forEach((machine, path) -> {
            if (out.containsKey(machine)) {
                throw new IllegalStateException("maquina " + machine + " em atlas.servicesyml.files e em atlas.servicesyml.github.paths: escolha uma fonte");
            }
            out.put(machine, github.apply(repo.get(), path));
        });
        return out;
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
