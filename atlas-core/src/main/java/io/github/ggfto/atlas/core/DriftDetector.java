package io.github.ggfto.atlas.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Selos de drift (design §3.3) sobre o mapa ja mesclado. Cada regra so vale com a fonte que a sustenta presente:
 * sem dado do Argo nao ha "sem-application", sem dado da Cloudflare nao ha "servico-sem-dns".
 * <ul>
 * <li>{@code outofsync}: app do Argo fora de sync, e os workloads que ela gerencia;</li>
 * <li>{@code sem-application}: workload que nenhuma app do Argo gerencia (fora dos namespaces do sistema);</li>
 * <li>{@code imagem-flutuante}: imagem {@code :latest} ou sem tag (digest e tag fixa nao contam);</li>
 * <li>{@code servico-sem-dns} / {@code dns-sem-servico}: services.yml x registros da Cloudflare;</li>
 * <li>{@code rota-protegida-aberta}: rota com auth que respondeu sem login, enquanto o check e fresco.</li>
 * </ul>
 */
public final class DriftDetector {

    static final Set<String> SYSTEM_NAMESPACES = Set.of("kube-system", "kube-public", "kube-node-lease");
    static final String OPEN_PROTECTED = "rota protegida respondeu sem login";

    private DriftDetector() {
    }

    public static List<TopologyNode> detect(List<TopologyNode> nodes, List<Edge> edges, Instant now) {
        Map<String, Node> byKey = new HashMap<>();
        nodes.forEach(t -> byKey.put(t.node().key(), t.node()));
        boolean argoKnown = nodes.stream().anyMatch(t -> t.node().kind() == NodeKind.ARGO);
        boolean dnsKnown = nodes.stream().anyMatch(t -> t.node().kind() == NodeKind.DNS);
        Set<String> managed = new HashSet<>();
        Set<String> outOfSync = new HashSet<>();
        for (Edge e : edges) {
            if (e.kind() == EdgeKind.MANAGES) {
                managed.add(e.to());
                Node app = byKey.get(e.from());
                if (app != null && isOutOfSync(app)) {
                    outOfSync.add(e.to());
                }
            }
        }
        List<TopologyNode> out = new ArrayList<>();
        for (TopologyNode t : nodes) {
            Node n = t.node();
            Set<String> flags = new TreeSet<>(t.drift());
            if ((n.kind() == NodeKind.ARGO && isOutOfSync(n)) || outOfSync.contains(n.key())) {
                flags.add("outofsync");
            }
            if (argoKnown && n.kind() == NodeKind.WORKLOAD && !managed.contains(n.key())
                    && !SYSTEM_NAMESPACES.contains(n.attributes().getOrDefault("namespace", namespaceOf(n.key())))) {
                flags.add("sem-application");
            }
            if ((n.kind() == NodeKind.CONTAINER || n.kind() == NodeKind.POD || n.kind() == NodeKind.WORKLOAD)
                    && floating(n.attributes().get("image"))) {
                flags.add("imagem-flutuante");
            }
            if (dnsKnown && n.kind() == NodeKind.ROUTE && publicExposure(n.attributes().get("exposure"))
                    && !byKey.containsKey("dns:" + n.key().substring("route:".length()))) {
                flags.add("servico-sem-dns");
            }
            if (n.kind() == NodeKind.DNS && "true".equals(n.attributes().get("managed"))
                    && !byKey.containsKey("route:" + n.key().substring("dns:".length()))) {
                flags.add("dns-sem-servico");
            }
            if (n.kind() == NodeKind.ROUTE && t.status().sources().stream()
                    .anyMatch(s -> s.fresh() && s.reason() != null && s.reason().startsWith(OPEN_PROTECTED))) {
                flags.add("rota-protegida-aberta");
            }
            out.add(new TopologyNode(n, t.status(), List.copyOf(flags)));
        }
        return out;
    }

    private static boolean isOutOfSync(Node app) {
        String sync = app.attributes().get("sync");
        return sync != null && !"Synced".equals(sync);
    }

    /** Sem tag ou {@code :latest}; a porta de um registry ({@code host:5000/app}) nao e tag. */
    static boolean floating(String image) {
        if (image == null || image.isBlank() || image.contains("@sha256:")) {
            return false;
        }
        String last = image.substring(image.lastIndexOf('/') + 1);
        int colon = last.lastIndexOf(':');
        return colon < 0 || "latest".equals(last.substring(colon + 1));
    }

    private static boolean publicExposure(String exposure) {
        return "externo".equals(exposure) || "direto".equals(exposure) || "ambos".equals(exposure);
    }

    private static String namespaceOf(String key) {
        String[] p = key.split("/");
        return p.length > 1 ? p[1] : "";
    }
}
