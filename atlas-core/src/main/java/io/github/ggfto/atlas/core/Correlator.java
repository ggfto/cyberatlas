package io.github.ggfto.atlas.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Liga o servico declarado no services.yml ao que de fato atende o {@code backend}, sempre na mesma maquina. Sem
 * match, sem aresta (nunca "chuta"):
 * <ul>
 * <li>host do backend igual ao nome do container ou ao servico compose: {@code ROUTES_TO} ao container;</li>
 * <li>backend no proprio host ({@code host.docker.internal}, {@code 172.17.0.1}, loopback) numa porta que e
 * {@code nodePort} do k3s desta maquina (atributo {@code nodeports} que o edge poe no workload e nos pods):
 * {@code ROUTES_TO} ao workload e aos pods;</li>
 * <li>backend no proprio host fora da faixa de NodePort (30000-32767): processo do host, nem Docker nem k8s.
 * {@code RUNS_ON} a {@code machine:<m>}, a mesma relacao "onde roda" do workload -> pod.</li>
 * </ul>
 * Backend que nao casa vira drift no {@link DriftDetector} ({@code backend-container-inexistente},
 * {@code backend-nodeport-inexistente}), nao aresta.
 */
public final class Correlator {

    /** Nomes que, de dentro do Traefik em container, apontam para a propria maquina. */
    static final Set<String> HOST_ALIASES = Set.of("host.docker.internal", "172.17.0.1", "localhost", "127.0.0.1");
    static final int NODEPORT_MIN = 30000;
    static final int NODEPORT_MAX = 32767;

    /** Host e porta do backend; porta -1 quando o URL nao diz. */
    record Backend(String host, int port) {
        boolean onHost() {
            return HOST_ALIASES.contains(host);
        }

        boolean nodePortRange() {
            return port >= NODEPORT_MIN && port <= NODEPORT_MAX;
        }
    }

    private Correlator() {
    }

    public static List<Edge> edges(Collection<Node> nodes) {
        List<Edge> out = new ArrayList<>();
        for (Node s : nodes) {
            if (s.kind() != NodeKind.SERVICE || s.machine() == null) {
                continue;
            }
            Backend b = backend(s);
            if (b == null) {
                continue;
            }
            for (Node c : nodes) {
                if (c.kind() == NodeKind.CONTAINER && s.machine().equals(c.machine())
                        && (b.host().equals(lower(c.name())) || b.host().equals(lower(c.attributes().get("compose_service"))))) {
                    out.add(Edge.of(s.key(), c.key(), EdgeKind.ROUTES_TO));
                }
            }
            if (!b.onHost() || b.port() < 0) {
                continue;
            }
            if (b.nodePortRange()) {
                for (Node k : nodes) {
                    if ((k.kind() == NodeKind.WORKLOAD || k.kind() == NodeKind.POD) && s.machine().equals(k.machine())
                            && hasNodePort(k, b.port())) {
                        out.add(Edge.of(s.key(), k.key(), EdgeKind.ROUTES_TO));
                    }
                }
            } else {
                out.add(Edge.of(s.key(), "machine:" + s.machine(), EdgeKind.RUNS_ON));
            }
        }
        return out;
    }

    static boolean hasNodePort(Node k, int port) {
        String ports = k.attributes().get("nodeports");
        return ports != null && Arrays.stream(ports.split(",")).map(String::trim).anyMatch(String.valueOf(port)::equals);
    }

    static Backend backend(Node service) {
        String backend = service.attributes().get("backend");
        if (backend == null) {
            return null;
        }
        try {
            URI u = URI.create(backend.trim());
            String host = lower(u.getHost());
            return host == null ? null : new Backend(host, u.getPort());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
