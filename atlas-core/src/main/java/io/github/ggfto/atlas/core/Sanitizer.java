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

    public static final Sanitizer DEFAULT = new Sanitizer(Map.of(
            NodeKind.ENTRY, Set.of("via"),
            NodeKind.AUTHGATE, Set.of("provider"),
            NodeKind.ROUTE, Set.of("fqdn", "exposure", "auth", "url"),
            NodeKind.SERVICE, Set.of("backend", "exposure", "auth"),
            NodeKind.CONTAINER, Set.of("image", "state", "status", "health", "compose_project", "compose_service"),
            NodeKind.ROUTER, Set.of("rule", "service", "status", "provider"),
            NodeKind.POD, Set.of("namespace", "phase", "ready", "restarts", "owner", "image"),
            NodeKind.WORKLOAD, Set.of("namespace", "workload", "ready", "desired", "image")));

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
                out.put(k, IPV4.matcher(v).replaceAll("<ip>"));
            }
        });
        return new Node(node.key(), node.kind(), node.name(), node.machine(), out);
    }
}
