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

    static Node k8s(String machine, String ns, String kind, String name, String nodePorts) {
        NodeKind k = "pod".equals(kind) ? NodeKind.POD : NodeKind.WORKLOAD;
        Map<String, String> attrs = nodePorts == null ? Map.of("namespace", ns) : Map.of("namespace", ns, "nodeports", nodePorts);
        return new Node("k8s:" + machine + "/" + ns + "/" + kind + "/" + name, k, name, machine, attrs);
    }

    @Test
    void classeA_nodePortDoHostLigaAoWorkloadEAoPodDaquelaMaquina() {
        List<Edge> e = Correlator.edges(List.of(service("vps", "argocd", "http://host.docker.internal:30080"),
                k8s("vps", "argocd", "deployment", "argocd-server", "30080,30443"),
                k8s("vps", "argocd", "pod", "argocd-server-5b594474b6-p95sg", "30080,30443"),
                k8s("vps", "argocd", "deployment", "argocd-redis", null),
                k8s("vps", "atlas", "deployment", "atlas-hub", "30090,30843")));
        assertEquals(List.of(
                Edge.of("service:vps/argocd", "k8s:vps/argocd/deployment/argocd-server", EdgeKind.ROUTES_TO),
                Edge.of("service:vps/argocd", "k8s:vps/argocd/pod/argocd-server-5b594474b6-p95sg", EdgeKind.ROUTES_TO)), e);
    }

    @Test
    void classeA_gatewayDoDockerTambemEHost() {
        assertEquals(List.of(Edge.of("service:host/dozzle-k8s", "k8s:host/dozzle/deployment/dozzle", EdgeKind.ROUTES_TO)),
                Correlator.edges(List.of(service("host", "dozzle-k8s", "http://172.17.0.1:30082"),
                        k8s("host", "dozzle", "deployment", "dozzle", "30082"))));
    }

    @Test
    void classeA_nodePortDeOutraMaquinaNuncaLiga() {
        // o 30081 e o dozzle do k3s da VPS; o host so enxerga o proprio cluster
        assertEquals(List.of(), Correlator.edges(List.of(service("host", "dozzle-k8s", "http://host.docker.internal:30081"),
                k8s("vps", "dozzle", "deployment", "dozzle", "30081"))));
    }

    @Test
    void classeA_portaPrecisaSerExataNaListaDeNodePorts() {
        assertEquals(List.of(), Correlator.edges(List.of(service("vps", "atlas", "http://host.docker.internal:30090"),
                k8s("vps", "x", "deployment", "quase", "300901,3009"))));
    }

    @Test
    void classeB_processoDoHostRodaNaMaquina() {
        assertEquals(List.of(Edge.of("service:host/clink", "machine:host", EdgeKind.RUNS_ON)),
                Correlator.edges(List.of(service("host", "clink", "http://host.docker.internal:5000"),
                        k8s("host", "dozzle", "deployment", "dozzle", "30082"))));
    }

    @Test
    void classeC_nomeDeContainerQueNaoExisteContinuaSemAresta() {
        assertEquals(List.of(), Correlator.edges(List.of(service("host", "dozzle", "http://dozzle-dozzle-1:8080"),
                container("host", "pihole", Map.of("compose_service", "pihole")))));
    }
}
