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
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Cloudflare pela API com token so leitura (design §1.3: Zone:Read, DNS:Read, Cloudflare Tunnel:Read): os
 * registros DNS da zona, os tuneis e o ingress de cada um. Do registro sai so tipo, proxied e a classificacao
 * (tunel/proxy/lan/direto), nunca o IP; ids de tunel, zona e conta so ligam nos aqui dentro e nunca viram dado.
 * O tunel de uma maquina (o {@code tunnel_id} do services.yml dela) da o estado da entrada {@code entry:m/tunel}.
 */
public final class CloudflareSource implements TopologySource {

    public static final int MAX_PAGES = 50;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern IP = Pattern.compile("^[0-9a-fA-F:.]+$");
    private static final String TUNNEL_SUFFIX = ".cfargotunnel.com";

    private final URI api;
    private final String token;
    private final String account;
    private final Supplier<String> zone;
    private final Supplier<Map<String, String>> tunnelMachine;
    private final Duration timeout;
    private final Clock clock;
    private final Duration ttl;
    private final HttpClient http;

    public CloudflareSource(URI api, String token, String account, String zone, Map<String, String> tunnelMachine,
                            Duration timeout, Clock clock, Duration ttl) {
        this(api, token, account, () -> zone, () -> Map.copyOf(tunnelMachine), timeout, clock, ttl);
    }

    /**
     * Zona e tuneis -> maquina resolvidos a cada coleta (vem do services.yml, que pode estar no GitHub): falha ao
     * ler vira erro desta coleta, nunca do boot do hub.
     */
    public CloudflareSource(URI api, String token, String account, Supplier<String> zone, Supplier<Map<String, String>> tunnelMachine,
                            Duration timeout, Clock clock, Duration ttl) {
        this.api = api;
        this.token = token;
        this.account = account;
        this.zone = zone;
        this.tunnelMachine = tunnelMachine;
        this.timeout = timeout;
        this.clock = clock;
        this.ttl = ttl;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String name() {
        return "cloudflare";
    }

    @Override
    public String toString() {
        return "CloudflareSource[token omitido]";
    }

    @Override
    public Fragment collect() throws IOException, InterruptedException {
        String zone = this.zone.get();
        Map<String, String> tunnelMachine = this.tunnelMachine.get();
        if (zone == null || zone.isBlank()) {
            throw new IOException("cloudflare: zona nao definida (atlas.cloudflare.zone ou zone do services.yml)");
        }
        JsonNode zones = get("/client/v4/zones?name=" + zone).path("result");
        if (!zones.isArray() || zones.isEmpty()) {
            throw new IOException("cloudflare: zona " + zone + " nao encontrada para o token");
        }
        String zoneId = zones.get(0).path("id").asText();
        List<JsonNode> records = paged("/client/v4/zones/" + zoneId + "/dns_records", "dns_records");
        List<JsonNode> tunnels = paged("/client/v4/accounts/" + account + "/cfd_tunnel?is_deleted=false", "cfd_tunnel");
        Instant now = clock.instant();
        Map<String, String> tunnelKeyById = new HashMap<>();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<Observation> obs = new ArrayList<>();
        for (JsonNode t : tunnels) {
            String key = "cf-tunnel:" + t.path("name").asText();
            String status = t.path("status").asText("unknown");
            int conns = t.path("connections").size();
            tunnelKeyById.put(t.path("id").asText(), key);
            nodes.add(new Node(key, NodeKind.TUNNEL, t.path("name").asText(), null,
                    Map.of("status", status, "connections", String.valueOf(conns))));
            State s = switch (status) {
                case "healthy" -> State.ONLINE;
                case "degraded" -> State.DEGRADED;
                case "down", "inactive" -> State.OFFLINE;
                default -> State.UNKNOWN;
            };
            String reason = "tunel " + t.path("name").asText() + ": " + status + " (" + conns + " conexoes)";
            obs.add(new Observation(key, name(), s, reason, now, ttl));
            String machine = tunnelMachine.get(t.path("id").asText());
            if (machine != null) {
                obs.add(new Observation("entry:" + machine + "/tunel", name(), s, reason, now, ttl));
            }
            JsonNode ingress = get("/client/v4/accounts/" + account + "/cfd_tunnel/" + t.path("id").asText() + "/configurations")
                    .path("result").path("config").path("ingress");
            for (JsonNode rule : ingress) {
                String host = rule.path("hostname").asText("");
                if (!host.isEmpty()) {
                    edges.add(Edge.of(key, "route:" + host.toLowerCase(Locale.ROOT), EdgeKind.ROUTES_TO));
                }
            }
        }
        for (JsonNode r : records) {
            String fqdn = r.path("name").asText().toLowerCase(Locale.ROOT);
            String type = r.path("type").asText();
            String content = r.path("content").asText("");
            boolean proxied = r.path("proxied").asBoolean(false);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("type", type);
            attrs.put("proxied", String.valueOf(proxied));
            attrs.put("via", via(type, content, proxied));
            attrs.put("managed", String.valueOf(r.path("comment").asText("").contains("gerenciado por")));
            String key = "dns:" + fqdn;
            nodes.add(new Node(key, NodeKind.DNS, fqdn, null, attrs));
            if ("CNAME".equals(type) && content.endsWith(TUNNEL_SUFFIX)) {
                String tunnel = tunnelKeyById.get(content.substring(0, content.length() - TUNNEL_SUFFIX.length()));
                if (tunnel != null) {
                    edges.add(Edge.of(key, tunnel, EdgeKind.ROUTES_TO));
                }
            }
        }
        return new Fragment(name(), now, ttl, nodes, edges, obs);
    }

    static String via(String type, String content, boolean proxied) {
        if ("CNAME".equals(type)) {
            return content.endsWith(TUNNEL_SUFFIX) ? "tunel" : proxied ? "proxy" : "cname";
        }
        if (proxied) {
            return "proxy";
        }
        if (("A".equals(type) || "AAAA".equals(type)) && IP.matcher(content).matches()) {
            try {
                InetAddress a = InetAddress.getByName(content);
                return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() ? "lan" : "direto";
            } catch (IOException e) {
                return "direto";
            }
        }
        return "direto";
    }

    private List<JsonNode> paged(String path, String label) throws IOException, InterruptedException {
        List<JsonNode> out = new ArrayList<>();
        String sep = path.contains("?") ? "&" : "?";
        for (int page = 1; ; page++) {
            JsonNode body = get(path + sep + "page=" + page + "&per_page=100");
            body.path("result").forEach(out::add);
            int total = body.path("result_info").path("total_pages").asInt(1);
            if (total > MAX_PAGES) {
                throw new IOException("cloudflare: mais de " + MAX_PAGES + " paginas em " + label);
            }
            if (page >= total) {
                return out;
            }
        }
    }

    private JsonNode get(String pathAndQuery) throws IOException, InterruptedException {
        String shown = ID.matcher(pathAndQuery.split("\\?")[0]).replaceAll("{id}");
        HttpRequest req = HttpRequest.newBuilder(api.resolve(pathAndQuery)).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IOException("cloudflare GET " + shown + ": timeout");
        }
        JsonNode body;
        try {
            body = JSON.readTree(resp.body());
        } catch (IOException e) {
            throw new IOException("cloudflare GET " + shown + ": HTTP " + resp.statusCode() + " (resposta nao e JSON)");
        }
        if (resp.statusCode() != 200 || !body.path("success").asBoolean(false)) {
            JsonNode err = body.path("errors").path(0);
            String detail = err.isMissingNode() ? "sem detalhe" : err.path("code").asText() + " " + err.path("message").asText();
            throw new IOException("cloudflare GET " + shown + ": HTTP " + resp.statusCode() + " (" + detail + ")");
        }
        return body;
    }
}
