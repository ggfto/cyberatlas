package io.github.ggfto.atlas.core;

import com.google.re2j.Pattern;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Allowlist de atributos por tipo de no (design §4): o que nao esta declarado nao sai do hub, seja Env,
 * {@code tunnel_id}, IP publico ou o que um adapter novo trouxer. IPv4 literal e mascarado mesmo num campo
 * permitido.
 */
public final class Sanitizer {

    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    public static final Sanitizer DEFAULT = new Sanitizer(Map.ofEntries(
            Map.entry(NodeKind.ENTRY, Set.of("via")),
            Map.entry(NodeKind.AUTHGATE, Set.of("provider")),
            Map.entry(NodeKind.ROUTE, Set.of("fqdn", "exposure", "auth", "url")),
            // icon e um override declarativo do services.yml (slug de dashboard-icons): nao e segredo nem
            // pista de rede, e o atlas-web precisa dele para pintar o icone certo no mapa (issue #24).
            Map.entry(NodeKind.SERVICE, Set.of("backend", "exposure", "auth", "icon")),
            Map.entry(NodeKind.CONTAINER, Set.of("image", "state", "status", "health", "compose_project", "compose_service")),
            Map.entry(NodeKind.ROUTER, Set.of("rule", "service", "status", "provider")),
            Map.entry(NodeKind.POD, Set.of("namespace", "phase", "ready", "restarts", "owner", "image", "nodeports")),
            Map.entry(NodeKind.WORKLOAD, Set.of("namespace", "workload", "ready", "desired", "image", "nodeports")),
            // Cloudflare: nunca o conteudo do registro (IP), o id do tunel nem ids de conta/zona
            Map.entry(NodeKind.TUNNEL, Set.of("status", "connections")),
            Map.entry(NodeKind.DNS, Set.of("type", "proxied", "via", "managed")),
            // Argo: nunca o destination.server (URL da API do cluster, IP do WG)
            Map.entry(NodeKind.ARGO, Set.of("sync", "health", "namespace"))));

    private final Map<NodeKind, Set<String>> allowed;

    public Sanitizer(Map<NodeKind, Set<String>> allowed) {
        this.allowed = new EnumMap<>(NodeKind.class);
        this.allowed.putAll(allowed);
    }

    public Node sanitize(Node node) {
        Set<String> keys = allowed.getOrDefault(node.kind(), Set.of());
        Map<String, String> out = new LinkedHashMap<>();
        node.attributes().forEach((k, v) -> {
            if (keys.contains(k) && v != null) {
                out.put(k, maskIpv4(v));
            }
        });
        return new Node(node.key(), node.kind(), node.name(), node.machine(), out);
    }

    /** IPv4 literal vira {@code <ip>}: a mesma mascara dos atributos, para quem precisa dela em texto livre. */
    public static String maskIpv4(String text) {
        return text == null ? null : IPV4.matcher(text).replaceAll("<ip>");
    }
}
