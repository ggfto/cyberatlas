package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Liga o servico declarado (services.yml) ao container real (edge) pelo host do backend (design §2.2, chaves). */
class CorrelatorTest {

    static Node service(String machine, String name, String backend) {
        return new Node("service:" + machine + "/" + name, NodeKind.SERVICE, name, machine, Map.of("backend", backend));
    }

    static Node container(String machine, String name, Map<String, String> attrs) {
        return new Node("docker:" + machine + "/" + name, NodeKind.CONTAINER, name, machine, attrs);
    }

    @Test
    void backendHostMatchesTheContainerName() {
        List<Edge> e = Correlator.edges(List.of(service("vps", "9router", "http://9router:20128"),
                container("vps", "9router", Map.of())));
        assertEquals(List.of(Edge.of("service:vps/9router", "docker:vps/9router", EdgeKind.ROUTES_TO)), e);
    }

    @Test
    void orTheComposeServiceName() {
        List<Edge> e = Correlator.edges(List.of(service("host", "dozzle", "http://dozzle:8080"),
                container("host", "dozzle-dozzle-1", Map.of("compose_service", "dozzle"))));
        assertEquals(List.of(Edge.of("service:host/dozzle", "docker:host/dozzle-dozzle-1", EdgeKind.ROUTES_TO)), e);
    }

    @Test
    void neverAcrossMachines() {
        assertEquals(List.of(), Correlator.edges(List.of(service("vps", "x", "http://x:80"), container("host", "x", Map.of()))));
    }

    @Test
    void noBackendOrNoMatchIsNoEdge() {
        assertEquals(List.of(), Correlator.edges(List.of(
                new Node("service:vps/a", NodeKind.SERVICE, "a", "vps", Map.of()),
                service("vps", "b", "http://host.docker.internal:30080"),
                service("vps", "c", "nao e url"),
                container("vps", "outro", Map.of()))));
    }
}
