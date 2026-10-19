package io.github.ggfto.atlas.edge;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.ggfto.atlas.adapters.edge.DockerClient;
import io.github.ggfto.atlas.adapters.edge.DockerLogSource;
import io.github.ggfto.atlas.adapters.edge.KubernetesLogSource;
import io.github.ggfto.atlas.adapters.edge.DockerSource;
import io.github.ggfto.atlas.adapters.edge.KubernetesSource;
import io.github.ggfto.atlas.adapters.edge.LocalChecks;
import io.github.ggfto.atlas.adapters.edge.TraefikSource;
import io.github.ggfto.atlas.core.Expectation;
import io.github.ggfto.atlas.core.LogSource;
import io.github.ggfto.atlas.core.Redactor;
import io.github.ggfto.atlas.core.TopologySource;
import io.github.ggfto.atlas.protocol.Backoff;
import io.github.ggfto.atlas.protocol.EdgeCredentials;
import io.github.ggfto.atlas.protocol.EdgeLink;
import io.github.ggfto.atlas.protocol.EnrollmentClient;
import io.github.ggfto.atlas.protocol.Frame;
import io.github.ggfto.atlas.protocol.Pem;
import io.github.ggfto.atlas.protocol.ProtocolVersion;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * O edge: credencial (carrega ou faz o enrollment uma vez), fontes locais configuradas e o link com o hub. A
 * cada Welcome reprograma a coleta no intervalo e com os checks locais que o hub mandou.
 */
@ApplicationScoped
public class EdgeAgent {

    private static final Logger LOG = Logger.getLogger(EdgeAgent.class);

    private final EdgeConfig config;
    private final Instance<KubernetesClient> kubernetes;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private volatile List<LocalChecks.Target> checks = List.of();
    private volatile ScheduledFuture<?> collecting;
    private volatile Duration ttl = Duration.ofSeconds(90);
    private EdgeLink link;

    EdgeAgent(EdgeConfig config, Instance<KubernetesClient> kubernetes) {
        this.config = config;
        this.kubernetes = kubernetes;
    }

    void onStart(@Observes StartupEvent event) {
        if (config.machine().isEmpty() || config.hub().isEmpty()) {
            LOG.info("edge sem atlas.edge.machine/hub: ocioso");
            return;
        }
        try {
            EdgeCredentials creds = credentials();
            String edgeId = Pem.readCertificates(creds.certPem()).get(0).getSubjectX500Principal().getName().substring(3);
            List<TopologySource> sources = sources();
            URI hub = URI.create(config.hub().get());
            URI ws = URI.create("wss://" + hub.getAuthority() + "/edge/v1");
            SnapshotPump pump = new SnapshotPump(() -> withChecks(sources), f -> link.send(f));
            EdgeLogs logs = new EdgeLogs(logSources(), f -> link.send(f), Redactor.DEFAULT, 20, 2000, 200, 64 * 1024);
            link = new EdgeLink(ws, creds.sslContext(),
                    () -> new Frame.Hello(edgeId, ProtocolVersion.CURRENT, config.machine().get(),
                            sources.stream().map(TopologySource::name).toList(), Map.of()),
                    new Backoff(Duration.ofSeconds(1), Duration.ofMinutes(2), Math::random), Duration.ofSeconds(20),
                    Duration.ofHours(1), new EdgeLink.Handler() {
                        public void onWelcome(Frame.Welcome w) {
                            checks = w.checks().stream().map(EdgeAgent::target).toList();
                            ttl = Duration.ofMillis(w.ttlMs());
                            reschedule(pump, Duration.ofMillis(w.collectEveryMs()));
                        }

                        public void onLogOpen(Frame.LogOpen o) {
                            logs.open(o);
                        }

                        public void onCredit(Frame.Credit c) {
                            logs.credit(c);
                        }

                        public void onLogClose(Frame.LogClose c) {
                            logs.close(c);
                        }

                        public void onDisconnect() {
                            logs.closeAll();
                        }
                    });
            link.start();
            LOG.infof("edge %s (%s) iniciado com %s", edgeId, config.machine().get(), sources.stream().map(TopologySource::name).toList());
        } catch (Exception e) {
            LOG.errorf("edge nao iniciou: %s", e.getMessage());
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        if (link != null) {
            link.close();
        }
        timer.shutdownNow();
    }

    private EdgeCredentials credentials() throws Exception {
        CredentialStore store = new CredentialStore(Path.of(config.stateDir()));
        var saved = store.load();
        if (saved.isPresent()) {
            return saved.get();
        }
        if (config.token().isEmpty() || config.caFile().isEmpty()) {
            throw new IllegalStateException("sem credencial em " + config.stateDir() + " e sem atlas.edge.token/ca-file para o enrollment");
        }
        EdgeCredentials c = EnrollmentClient.enroll(URI.create(config.hub().get()), config.token().get(),
                Files.readString(Path.of(config.caFile().get())), config.timeout());
        store.save(c);
        LOG.info("enrollment feito; o token nao e mais necessario");
        return c;
    }

    private List<TopologySource> sources() {
        String m = config.machine().get();
        Clock clock = Clock.systemUTC();
        List<TopologySource> out = new ArrayList<>();
        config.docker().ifPresent(d -> out.add(new DockerSource(m, DockerClient.of(URI.create(d), config.timeout()), clock, ttl)));
        config.traefik().ifPresent(t -> out.add(new TraefikSource(m, URI.create(t), config.timeout(), clock, ttl)));
        if (config.kubernetes()) {
            out.add(new KubernetesSource(m, kubernetes.get(), clock, ttl));
        }
        return out;
    }

    private List<LogSource> logSources() {
        String m = config.machine().get();
        List<LogSource> out = new ArrayList<>();
        config.docker().ifPresent(d -> out.add(new DockerLogSource(m, DockerClient.of(URI.create(d), config.timeout()))));
        if (config.kubernetes()) {
            out.add(new KubernetesLogSource(m, kubernetes.get()));
        }
        return out;
    }

    private List<TopologySource> withChecks(List<TopologySource> sources) {
        List<TopologySource> all = new ArrayList<>(sources);
        if (!checks.isEmpty()) {
            all.add(new LocalChecks(() -> checks, config.timeout(), Clock.systemUTC(), ttl));
        }
        return all;
    }

    private synchronized void reschedule(SnapshotPump pump, Duration every) {
        if (collecting != null) {
            collecting.cancel(false);
        }
        collecting = timer.scheduleAtFixedRate(pump::pump, 0, every.toMillis(), TimeUnit.MILLISECONDS);
    }

    static LocalChecks.Target target(Frame.LocalCheck c) {
        Expectation e = c.protectedRoute() ? new Expectation.Protected(c.authHost()) : new Expectation.Open(c.authHost());
        return new LocalChecks.Target(c.nodeKey(), URI.create(c.url()), e);
    }
}
