package io.github.ggfto.atlas.adapters.edge;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API do Traefik da propria maquina, pela rede interna (design §1.2; dispensa expor {@code api@internal} no WG).
 * Le so {@code /api/http/routers} e {@code /api/http/services}: o {@code serverStatus} do service vira o estado do
 * upstream em cada rota {@code Host(...)}. {@code /api/http/middlewares} (hashes de basicAuth) nunca e lido.
 */
public final class TraefikSource implements TopologySource {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HOST = Pattern.compile("Host\\(([^)]*)\\)");

    private final String machine;
    private final URI base;
    private final Duration timeout;
    private final Clock clock;
    private final Duration ttl;
    private final HttpClient http;

    public TraefikSource(String machine, URI base, Duration timeout, Clock clock, Duration ttl) {
        this.machine = machine;
        this.base = base;
        this.timeout = timeout;
        this.clock = clock;
        this.ttl = ttl;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String name() {
        return "traefik";
    }

    @Override
    public Fragment collect() throws IOException, InterruptedException {
        JsonNode routers = get("/api/http/routers");
        JsonNode services = get("/api/http/services");
        Map<String, JsonNode> serviceByName = new HashMap<>();
        services.forEach(s -> serviceByName.put(s.path("name").asText(), s));
        Instant now = clock.instant();
        String traefik = "traefik:" + machine;
        List<Node> nodes = new ArrayList<>(List.of(new Node(traefik, NodeKind.TRAEFIK, "Traefik", machine, Map.of())));
        List<Edge> edges = new ArrayList<>();
        List<Observation> obs = new ArrayList<>(List.of(new Observation(traefik, name(), State.ONLINE, "API respondeu", now, ttl)));
        for (JsonNode r : routers) {
            if ("internal".equals(r.path("provider").asText())) {
                continue;
            }
            String name = r.path("name").asText();
            String key = "router:" + machine + "/" + name;
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("rule", r.path("rule").asText());
            attrs.put("service", r.path("service").asText());
            attrs.put("status", r.path("status").asText());
            attrs.put("provider", r.path("provider").asText());
            nodes.add(new Node(key, NodeKind.ROUTER, name, machine, attrs));
            edges.add(Edge.of(traefik, key, EdgeKind.ROUTES_TO));
            Observation template = judge(r, serviceByName.get(r.path("service").asText()), now);
            if (template != null) {
                for (String host : hosts(r.path("rule").asText())) {
                    obs.add(new Observation("route:" + host, name(), template.state(), template.reason(), now, ttl));
                }
            }
        }
        return new Fragment(name(), now, ttl, nodes, edges, obs);
    }

    /** Estado do upstream do router; {@code null} quando o Traefik nao tem opiniao (service sem healthcheck). */
    private Observation judge(JsonNode router, JsonNode service, Instant now) {
        JsonNode errors = router.path("error");
        if (errors.isArray() && !errors.isEmpty()) {
            return new Observation("", name(), State.DEGRADED, "router com erro: " + errors.get(0).asText(), now, ttl);
        }
        if (!"enabled".equals(router.path("status").asText())) {
            return new Observation("", name(), State.DEGRADED, "router " + router.path("status").asText(), now, ttl);
        }
        JsonNode status = service == null ? null : service.path("serverStatus");
        if (status == null || !status.isObject() || status.isEmpty()) {
            return null;
        }
        int total = status.size();
        int up = 0;
        for (JsonNode v : status) {
            if ("UP".equals(v.asText())) {
                up++;
            }
        }
        State s = up == total ? State.ONLINE : up == 0 ? State.OFFLINE : State.DEGRADED;
        return new Observation("", name(), s, "upstream: " + up + "/" + total + " servidores UP", now, ttl);
    }

    static List<String> hosts(String rule) {
        List<String> out = new ArrayList<>();
        Matcher m = HOST.matcher(rule);
        while (m.find()) {
            for (String h : m.group(1).split(",")) {
                String clean = h.replaceAll("[`\"'\\s]", "");
                if (!clean.isEmpty()) {
                    out.add(clean.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return out;
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("traefik GET " + path + ": HTTP " + resp.statusCode());
        }
        return JSON.readTree(resp.body());
    }
}
