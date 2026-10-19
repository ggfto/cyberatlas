package io.github.ggfto.atlas.adapters.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.TopologySource;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Argo CD com conta local so leitura (design §1.3: {@code applications, get} + {@code clusters, get}). Cada app vira
 * no com sync e health, e uma aresta "gerencia" ate cada workload dela na maquina do cluster de destino. O
 * {@code destination.server} (a URL da API do cluster, com o IP do WG) so serve para achar a maquina.
 */
public final class ArgoSource implements TopologySource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> WORKLOADS = Set.of("Deployment", "StatefulSet", "DaemonSet");

    private final URI base;
    private final String token;
    private final Map<String, String> clusterMachine;
    private final Duration timeout;
    private final Clock clock;
    private final Duration ttl;
    private final HttpClient http;

    public ArgoSource(URI base, String token, Map<String, String> clusterMachine, Duration timeout, Clock clock, Duration ttl) {
        this.base = base;
        this.token = token;
        this.clusterMachine = Map.copyOf(clusterMachine);
        this.timeout = timeout;
        this.clock = clock;
        this.ttl = ttl;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String name() {
        return "argocd";
    }

    @Override
    public String toString() {
        return "ArgoSource[token omitido]";
    }

    @Override
    public Fragment collect() throws IOException, InterruptedException {
        String path = "/api/v1/applications";
        HttpRequest req = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IOException("argocd GET " + path + ": timeout");
        }
        if (resp.statusCode() != 200) {
            throw new IOException("argocd GET " + path + ": HTTP " + resp.statusCode());
        }
        Instant now = clock.instant();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<Observation> obs = new ArrayList<>();
        for (JsonNode app : JSON.readTree(resp.body()).path("items")) {
            String name = app.path("metadata").path("name").asText();
            String key = "argo:" + name;
            JsonNode dest = app.path("spec").path("destination");
            String machine = clusterMachine.get(dest.path("name").asText(""));
            if (machine == null) {
                machine = clusterMachine.get(dest.path("server").asText(""));
            }
            String sync = app.path("status").path("sync").path("status").asText("Unknown");
            String health = app.path("status").path("health").path("status").asText("Unknown");
            nodes.add(new Node(key, NodeKind.ARGO, name, machine,
                    Map.of("sync", sync, "health", health, "namespace", dest.path("namespace").asText(""))));
            obs.add(new Observation(key, name(), state(sync, health), sync + "/" + health, now, ttl));
            if (machine == null) {
                continue;
            }
            for (JsonNode r : app.path("status").path("resources")) {
                String kind = r.path("kind").asText();
                if (WORKLOADS.contains(kind)) {
                    edges.add(Edge.of(key, "k8s:" + machine + "/" + r.path("namespace").asText() + "/"
                            + kind.toLowerCase(Locale.ROOT) + "/" + r.path("name").asText(), EdgeKind.MANAGES));
                }
            }
        }
        return new Fragment(name(), now, ttl, nodes, edges, obs);
    }

    private static State state(String sync, String health) {
        return switch (health) {
            case "Missing" -> State.OFFLINE;
            case "Degraded", "Progressing", "Suspended" -> State.DEGRADED;
            case "Healthy" -> "Synced".equals(sync) ? State.ONLINE : State.DEGRADED;
            default -> State.UNKNOWN;
        };
    }
}
