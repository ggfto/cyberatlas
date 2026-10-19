package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Drift (design §3.3): selos que dizem "isso diverge do pretendido". Cada regra so vale com a fonte presente. */
class DriftDetectorTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");

    static TopologyNode tn(String key, NodeKind kind, Map<String, String> attrs, NodeStatus status) {
        return new TopologyNode(new Node(key, kind, key, "vps", attrs), status, List.of());
    }

    static TopologyNode tn(String key, NodeKind kind, Map<String, String> attrs) {
        return tn(key, kind, attrs, new NodeStatus(State.UNKNOWN, "sem fonte", List.of()));
    }

    static Map<String, List<String>> drift(List<TopologyNode> nodes, List<Edge> edges) {
        return DriftDetector.detect(nodes, edges, AT).stream().collect(Collectors.toMap(n -> n.node().key(), TopologyNode::drift));
    }

    @Test
    void outOfSyncAppAndTheWorkloadsItManages() {
        Map<String, List<String>> d = drift(List.of(
                tn("argo:host-9router-lab", NodeKind.ARGO, Map.of("sync", "OutOfSync", "health", "Healthy")),
                tn("k8s:host/lab/deployment/9router-lab", NodeKind.WORKLOAD, Map.of("image", "ghcr.io/ggfto/9router@sha256:abc"))),
                List.of(Edge.of("argo:host-9router-lab", "k8s:host/lab/deployment/9router-lab", EdgeKind.MANAGES)));
        assertEquals(List.of("outofsync"), d.get("argo:host-9router-lab"));
        assertEquals(List.of("outofsync"), d.get("k8s:host/lab/deployment/9router-lab"));
    }

    @Test
    void workloadWithoutApplicationOnlyWhenArgoIsKnown() {
        TopologyNode guestbook = tn("k8s:host/argo-teste/deployment/guestbook-ui", NodeKind.WORKLOAD, Map.of("namespace", "argo-teste",
                "image", "x@sha256:1"));
        TopologyNode system = tn("k8s:host/kube-system/deployment/coredns", NodeKind.WORKLOAD, Map.of("namespace", "kube-system",
                "image", "x@sha256:1"));
        TopologyNode app = tn("argo:host-dozzle", NodeKind.ARGO, Map.of("sync", "Synced"));
        assertEquals(List.of("sem-application"), drift(List.of(guestbook, system, app), List.of()).get(guestbook.node().key()));
        assertEquals(List.of(), drift(List.of(guestbook, system, app), List.of()).get(system.node().key()), "kube-system nao conta");
        assertEquals(List.of(), drift(List.of(guestbook), List.of()).get(guestbook.node().key()), "sem Argo, sem opiniao");
    }

    @Test
    void floatingImageIsLatestOrUntaggedNeverDigestOrPinnedTag() {
        Map<String, List<String>> d = drift(List.of(
                tn("docker:vps/a", NodeKind.CONTAINER, Map.of("image", "ghcr.io/ggfto/9router:latest")),
                tn("docker:vps/b", NodeKind.CONTAINER, Map.of("image", "pihole/pihole")),
                tn("docker:vps/c", NodeKind.CONTAINER, Map.of("image", "localhost:5000/app")),
                tn("docker:vps/d", NodeKind.CONTAINER, Map.of("image", "ghcr.io/goauthentik/server:2025.2.4")),
                tn("docker:vps/e", NodeKind.CONTAINER, Map.of("image", "ghcr.io/x/y@sha256:0123")),
                tn("k8s:vps/x/pod/p", NodeKind.POD, Map.of("image", "traefik:v3"))), List.of());
        assertEquals(List.of("imagem-flutuante"), d.get("docker:vps/a"));
        assertEquals(List.of("imagem-flutuante"), d.get("docker:vps/b"));
        assertEquals(List.of("imagem-flutuante"), d.get("docker:vps/c"), "porta do registry nao e tag");
        assertEquals(List.of(), d.get("docker:vps/d"));
        assertEquals(List.of(), d.get("docker:vps/e"));
        assertEquals(List.of(), d.get("k8s:vps/x/pod/p"), "tag de major e tag (o digest e outra discussao)");
    }

    @Test
    void dnsAgainstServicesYmlBothWays() {
        Map<String, List<String>> d = drift(List.of(
                tn("route:ha.gf2.in", NodeKind.ROUTE, Map.of("exposure", "externo")),
                tn("route:sem-dns.gf2.in", NodeKind.ROUTE, Map.of("exposure", "externo")),
                tn("route:fx.local.gf2.in", NodeKind.ROUTE, Map.of("exposure", "local")),
                tn("dns:ha.gf2.in", NodeKind.DNS, Map.of("managed", "true")),
                tn("dns:orfao.gf2.in", NodeKind.DNS, Map.of("managed", "true")),
                tn("dns:manual.gf2.in", NodeKind.DNS, Map.of("managed", "false"))), List.of());
        assertEquals(List.of(), d.get("route:ha.gf2.in"));
        assertEquals(List.of("servico-sem-dns"), d.get("route:sem-dns.gf2.in"));
        assertEquals(List.of(), d.get("route:fx.local.gf2.in"), "rota local nao passa pela Cloudflare publica");
        assertEquals(List.of("dns-sem-servico"), d.get("dns:orfao.gf2.in"));
        assertEquals(List.of(), d.get("dns:manual.gf2.in"), "registro manual nao e do services.yml");
    }

    @Test
    void withoutCloudflareDataThereIsNoDnsDrift() {
        assertEquals(List.of(), drift(List.of(tn("route:sem-dns.gf2.in", NodeKind.ROUTE, Map.of("exposure", "externo"))), List.of())
                .get("route:sem-dns.gf2.in"));
    }

    @Test
    void protectedRouteAnsweringWithoutLoginIsFlaggedWhileFresh() {
        NodeStatus aberta = new NodeStatus(State.DEGRADED, "rota protegida respondeu sem login (200)", List.of(
                new SourceState("check:publico", State.DEGRADED, "rota protegida respondeu sem login (200)", AT, true)));
        NodeStatus velha = new NodeStatus(State.UNKNOWN, "nenhuma fonte fresca", List.of(
                new SourceState("check:publico", State.DEGRADED, "rota protegida respondeu sem login (200)", AT, false)));
        assertEquals(List.of("rota-protegida-aberta"), drift(List.of(tn("route:x", NodeKind.ROUTE, Map.of(), aberta)), List.of()).get("route:x"));
        assertEquals(List.of(), drift(List.of(tn("route:x", NodeKind.ROUTE, Map.of(), velha)), List.of()).get("route:x"));
    }

    @Test
    void severalFlagsAreSortedAndUnique() {
        Map<String, List<String>> d = drift(List.of(
                tn("argo:a", NodeKind.ARGO, Map.of("sync", "OutOfSync")),
                tn("k8s:vps/n/deployment/w", NodeKind.WORKLOAD, Map.of("image", "x:latest"))),
                List.of(Edge.of("argo:a", "k8s:vps/n/deployment/w", EdgeKind.MANAGES), Edge.of("argo:a", "k8s:vps/n/deployment/w", EdgeKind.MANAGES)));
        assertEquals(List.of("imagem-flutuante", "outofsync"), d.get("k8s:vps/n/deployment/w"));
    }

    @Test
    void backendContainerInexistenteSoComODockerDaMaquinaPresente() {
        TopologyNode dozzle = tn("service:vps/dozzle", NodeKind.SERVICE, Map.of("backend", "http://dozzle-dozzle-1:8080"));
        TopologyNode portainer = tn("service:vps/portainer", NodeKind.SERVICE, Map.of("backend", "http://portainer:9000"));
        TopologyNode externo = tn("service:vps/ext", NodeKind.SERVICE, Map.of("backend", "https://api.example.com"));
        TopologyNode ctr = tn("docker:vps/portainer", NodeKind.CONTAINER, Map.of("image", "x@sha256:1"));
        List<Edge> e = List.of(Edge.of("service:vps/portainer", "docker:vps/portainer", EdgeKind.ROUTES_TO));
        Map<String, List<String>> d = drift(List.of(dozzle, portainer, externo, ctr), e);
        assertEquals(List.of("backend-container-inexistente"), d.get("service:vps/dozzle"));
        assertEquals(List.of(), d.get("service:vps/portainer"));
        assertEquals(List.of(), d.get("service:vps/ext"), "FQDN externo nao e nome de container");
        assertEquals(List.of(), drift(List.of(dozzle), List.of()).get("service:vps/dozzle"), "sem Docker da maquina, sem opiniao");
    }

    @Test
    void backendNodePortInexistenteSoComNodePortsDaMaquinaPresentes() {
        TopologyNode atlas = tn("service:vps/atlas", NodeKind.SERVICE, Map.of("backend", "http://host.docker.internal:30091"));
        TopologyNode clink = tn("service:vps/clink", NodeKind.SERVICE, Map.of("backend", "http://host.docker.internal:5000"));
        TopologyNode hub = tn("k8s:vps/atlas/deployment/atlas-hub", NodeKind.WORKLOAD, Map.of("namespace", "atlas", "nodeports", "30090",
                "image", "x@sha256:1"));
        Map<String, List<String>> d = drift(List.of(atlas, clink, hub), List.of());
        assertEquals(List.of("backend-nodeport-inexistente"), d.get("service:vps/atlas"));
        assertEquals(List.of(), d.get("service:vps/clink"), "fora da faixa de NodePort e processo do host");
        assertEquals(List.of(), drift(List.of(atlas), List.of()).get("service:vps/atlas"), "edge sem Services (RBAC antigo): sem opiniao");
    }
}
