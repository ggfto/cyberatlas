package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Um edge so escreve sobre a propria maquina: nada de nos ou estado de outra, nem fonte com nome do hub. */
class EdgeFragmentGuardTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");
    static final Duration TTL = Duration.ofSeconds(90);

    static Node n(String key, NodeKind kind, String machine) {
        return new Node(key, kind, key, machine, Map.of());
    }

    static Observation o(String key) {
        return new Observation(key, "x", State.ONLINE, "ok", AT, TTL);
    }

    @Test
    void keepsOwnMachineAndDropsTheRest() {
        Fragment in = new Fragment("servicesyml:host", AT, TTL,
                List.of(n("docker:vps/9router", NodeKind.CONTAINER, "vps"),
                        n("docker:host/fx-panel", NodeKind.CONTAINER, "host"),
                        n("docker:vps/mentira", NodeKind.CONTAINER, "host"),
                        n("route:ha.gf2.in", NodeKind.ROUTE, "vps"),
                        n("authgate:authentik", NodeKind.AUTHGATE, null)),
                List.of(Edge.of("traefik:vps", "docker:vps/9router", EdgeKind.ROUTES_TO),
                        Edge.of("docker:vps/9router", "docker:host/fx-panel", EdgeKind.DEPENDS_ON)),
                List.of(o("docker:vps/9router"), o("docker:host/fx-panel"), o("service:vps/9router"), o("service:host/x"),
                        o("route:ha.gf2.in"), o("route:fx.local.gf2.in"), o("authgate:authentik")));
        Map<String, String> routeMachine = Map.of("route:ha.gf2.in", "vps", "route:fx.local.gf2.in", "host");
        Fragment out = EdgeFragmentGuard.accept(in, "vps", routeMachine::get, AT, TTL);
        assertEquals("edge:vps/servicesyml:host", out.source());
        assertEquals(List.of("docker:vps/9router"), out.nodes().stream().map(Node::key).toList());
        assertEquals(List.of(Edge.of("traefik:vps", "docker:vps/9router", EdgeKind.ROUTES_TO)), out.edges());
        assertEquals(List.of("docker:vps/9router", "service:vps/9router", "route:ha.gf2.in"),
                out.observations().stream().map(Observation::nodeKey).toList());
        assertEquals(List.of("edge:vps/servicesyml:host"), out.observations().stream().map(Observation::source).distinct().toList());
    }

    @Test
    void traefikAndK8sKeysOfTheMachineAreAccepted() {
        Fragment in = new Fragment("kubernetes", AT, TTL,
                List.of(n("traefik:vps", NodeKind.TRAEFIK, "vps"), n("router:vps/ha@file", NodeKind.ROUTER, "vps"),
                        n("k8s:vps/dozzle/pod/d-1", NodeKind.POD, "vps"), n("k8s:host/x/pod/y", NodeKind.POD, "vps")),
                List.of(), List.of());
        assertEquals(List.of("traefik:vps", "router:vps/ha@file", "k8s:vps/dozzle/pod/d-1"),
                EdgeFragmentGuard.accept(in, "vps", k -> null, AT, TTL).nodes().stream().map(Node::key).toList());
    }

    @Test
    void edgeCannotKeepItsStateFreshForever() {
        // TTL de um ano ou relogio no futuro manteriam o no "online" depois de o edge cair
        Fragment in = new Fragment("docker", AT.plusSeconds(3600), Duration.ofDays(365),
                List.of(n("docker:vps/a", NodeKind.CONTAINER, "vps")), List.of(),
                List.of(new Observation("docker:vps/a", "docker", State.ONLINE, "ok", AT.plusSeconds(3600), Duration.ofDays(365))));
        Fragment out = EdgeFragmentGuard.accept(in, "vps", k -> null, AT, TTL);
        Observation o = out.observations().get(0);
        assertEquals(AT, o.observedAt());
        assertEquals(TTL, o.ttl());
        assertEquals(TTL, out.ttl());
        assertEquals(AT, out.observedAt());
    }
}
