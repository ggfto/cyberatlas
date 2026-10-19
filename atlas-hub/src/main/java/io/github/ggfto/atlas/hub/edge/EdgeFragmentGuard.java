package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.Observation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * O edge so escreve sobre a propria maquina (a do certificado, nao a que ele diz): nos com chave dela
 * ({@code docker:m/}, {@code k8s:m/}, {@code router:m/}, {@code traefik:m}), estado desses nos, dos servicos
 * {@code service:m/} e das rotas cuja maquina declarada no services.yml e m. A fonte e reescrita para
 * {@code edge:m/<fonte>}: um edge nao se passa por fonte do hub.
 */
public final class EdgeFragmentGuard {

    private EdgeFragmentGuard() {
    }

    public static Fragment accept(Fragment in, String machine, Function<String, String> routeMachine) {
        String source = "edge:" + machine + "/" + in.source();
        List<Node> nodes = in.nodes().stream()
                .filter(n -> machine.equals(n.machine()) && ownKey(n.key(), machine))
                .toList();
        Set<String> keys = new HashSet<>();
        nodes.forEach(n -> keys.add(n.key()));
        List<Edge> edges = in.edges().stream().filter(e -> ownKey(e.from(), machine) && ownKey(e.to(), machine)).toList();
        List<Observation> obs = in.observations().stream()
                .filter(o -> keys.contains(o.nodeKey())
                        || o.nodeKey().startsWith("service:" + machine + "/")
                        || (o.nodeKey().startsWith("route:") && machine.equals(routeMachine.apply(o.nodeKey()))))
                .map(o -> new Observation(o.nodeKey(), source, o.state(), o.reason(), o.observedAt(), o.ttl()))
                .toList();
        return new Fragment(source, in.observedAt(), in.ttl(), nodes, edges, obs);
    }

    private static boolean ownKey(String key, String m) {
        return key.startsWith("docker:" + m + "/") || key.startsWith("k8s:" + m + "/") || key.startsWith("router:" + m + "/")
                || key.equals("traefik:" + m);
    }
}
