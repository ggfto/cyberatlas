package io.github.ggfto.atlas.core;

import java.util.Objects;

/** Aresta entre duas chaves de no. O {@code id} e estavel para o mesmo par e tipo. */
public record Edge(String id, String from, String to, EdgeKind kind) {
    public Edge {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(kind, "kind");
    }

    public static Edge of(String from, String to, EdgeKind kind) {
        return new Edge(kind.name().toLowerCase() + ":" + from + "->" + to, from, to, kind);
    }
}
