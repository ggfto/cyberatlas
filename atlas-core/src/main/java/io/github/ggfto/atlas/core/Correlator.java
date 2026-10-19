package io.github.ggfto.atlas.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Liga o servico declarado no services.yml ao container que o edge enxerga: mesma maquina e o host do
 * {@code backend} igual ao nome do container ou ao servico compose. Sem match, sem aresta (nunca "chuta").
 */
public final class Correlator {

    private Correlator() {
    }

    public static List<Edge> edges(Collection<Node> nodes) {
        List<Edge> out = new ArrayList<>();
        for (Node s : nodes) {
            if (s.kind() != NodeKind.SERVICE) {
                continue;
            }
            String host = backendHost(s.attributes().get("backend"));
            if (host == null) {
                continue;
            }
            for (Node c : nodes) {
                if (c.kind() == NodeKind.CONTAINER && s.machine() != null && s.machine().equals(c.machine())
                        && (host.equals(lower(c.name())) || host.equals(lower(c.attributes().get("compose_service"))))) {
                    out.add(Edge.of(s.key(), c.key(), EdgeKind.ROUTES_TO));
                }
            }
        }
        return out;
    }

    private static String backendHost(String backend) {
        if (backend == null) {
            return null;
        }
        try {
            return lower(URI.create(backend.trim()).getHost());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
