package io.github.ggfto.atlas.core;

import java.util.Map;
import java.util.Objects;

/**
 * Um no descoberto. {@code key} e a chave sintetica estavel por fonte ({@code route:<fqdn>},
 * {@code service:<maquina>/<nome>}, {@code docker:<maquina>/<container>}...). {@code attributes} so sai do hub
 * depois do {@link Sanitizer}.
 */
public record Node(String key, NodeKind kind, String name, String machine, Map<String, String> attributes) {
    public Node {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
