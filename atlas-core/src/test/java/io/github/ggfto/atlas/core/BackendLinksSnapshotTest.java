package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Os 18 servicos do /api/topology de producao (2026-10-19, sanitizado), passando pelo merge inteiro: os 8 que ja
 * ligavam continuam ligados ao mesmo container, os 5 de NodePort ligam ao workload/pod do k3s da propria maquina, os 2
 * processos do host rodam na maquina e os 3 com container inexistente viram drift.
 */
class BackendLinksSnapshotTest {

    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    static Node svc(String m, String name, String backend) {
        return new Node("service:" + m + "/" + name, NodeKind.SERVICE, name, m,
                Map.of("backend", backend, "exposure", "externo", "auth", "true"));
    }

    static Node ctr(String m, String name, String composeService) {
        return new Node("docker:" + m + "/" + name, NodeKind.CONTAINER, name, m,
                composeService == null ? Map.of() : Map.of("compose_service", composeService));
    }

    static Node k8s(String m, String ns, String kind, String name, String owner, String nodePorts) {
        Map<String, String> a = new TreeMap<>(Map.of("namespace", ns));
        if (owner != null) {
            a.put("owner", owner);
        }
        if (nodePorts != null) {
            a.put("nodeports", nodePorts);
        }
        return new Node("k8s:" + m + "/" + ns + "/" + kind + "/" + name, "pod".equals(kind) ? NodeKind.POD : NodeKind.WORKLOAD,
                name, m, a);
    }

    static Fragment frag(String source, List<Node> nodes) {
        return new Fragment(source, NOW.minusSeconds(5), Duration.ofSeconds(90), nodes, List.of(), List.of());
    }

    static List<Fragment> snapshot() {
        List<Node> sy = new ArrayList<>(List.of(
                new Node("machine:host", NodeKind.MACHINE, "host", "host", Map.of()),
                new Node("machine:vps", NodeKind.MACHINE, "vps", "vps", Map.of()),
                svc("host", "9router-lab", "http://host.docker.internal:30129"),
                svc("host", "ai-office", "http://ai-office-dashboard:5180"),
                svc("host", "clink", "http://host.docker.internal:5000"),
                svc("host", "dozzle", "http://dozzle-dozzle-1:8080"),
                svc("host", "dozzle-k8s", "http://host.docker.internal:30082"),
                svc("host", "fx-panel", "http://fx-panel:8080"),
                svc("host", "hermes", "http://host.docker.internal:9119"),
                svc("host", "pihole", "http://pihole:80"),
                svc("host", "portainer", "http://portainer:9000"),
                svc("host", "txadmin", "http://fivem:40120"),
                svc("vps", "9router", "http://9router:20128"),
                svc("vps", "argocd", "http://host.docker.internal:30080"),
                svc("vps", "atlas", "http://host.docker.internal:30090"),
                svc("vps", "dozzle", "http://dozzle-dozzle-1:8080"),
                svc("vps", "dozzle-k8s", "http://host.docker.internal:30081"),
                svc("vps", "freellmapi", "http://freellmapi:3001"),
                svc("vps", "homeassistant", "http://homeassistant:8123"),
                svc("vps", "portainer", "http://portainer:9000")));
        List<Node> dockerHost = List.of(ctr("host", "9router-lab", "nineroute"), ctr("host", "cloudflared", "cloudflared"),
                ctr("host", "fivem", "fivem"), ctr("host", "fivem-db", "mariadb"), ctr("host", "fx-panel", "panel"),
                ctr("host", "homelable-lab-backend-1", "backend"), ctr("host", "pihole", "pihole"),
                ctr("host", "portainer", "portainer"), ctr("host", "traefik", "traefik"));
        List<Node> dockerVps = List.of(ctr("vps", "9router", "nineroute"), ctr("vps", "authentik-server", "server"),
                ctr("vps", "freellmapi", "freellmapi"), ctr("vps", "homeassistant", "homeassistant"), ctr("vps", "mosquitto", "mosquitto"),
                ctr("vps", "portainer", "portainer"), ctr("vps", "traefik", "traefik"), ctr("vps", "cloudflare", null));
        List<Node> k8sHost = List.of(
                k8s("host", "9router-lab", "deployment", "9router-lab", null, "30129"),
                k8s("host", "9router-lab", "pod", "9router-lab-5c847d5bdf-tzdmp", "Deployment/9router-lab", "30129"),
                k8s("host", "dozzle", "deployment", "dozzle", null, "30082"),
                k8s("host", "dozzle", "pod", "dozzle-85d885f8b-wf77r", "Deployment/dozzle", "30082"),
                k8s("host", "kube-system", "deployment", "coredns", null, null));
        List<Node> k8sVps = List.of(
                k8s("vps", "argocd", "deployment", "argocd-server", null, "30080,30443"),
                k8s("vps", "argocd", "pod", "argocd-server-5b594474b6-p95sg", "Deployment/argocd-server", "30080,30443"),
                k8s("vps", "argocd", "deployment", "argocd-repo-server", null, null),
                k8s("vps", "argocd", "statefulset", "argocd-application-controller", null, null),
                k8s("vps", "atlas", "deployment", "atlas-hub", null, "30090,30843"),
                k8s("vps", "atlas", "pod", "atlas-hub-f95675c74-hbj2d", "Deployment/atlas-hub", "30090,30843"),
                k8s("vps", "atlas", "statefulset", "atlas-db", null, null),
                k8s("vps", "dozzle", "deployment", "dozzle", null, "30081"),
                k8s("vps", "dozzle", "pod", "dozzle-79df8cd4c9-9pzfc", "Deployment/dozzle", "30081"));
        return List.of(frag("servicesyml", sy), frag("edge:host/docker", dockerHost), frag("edge:vps/docker", dockerVps),
                frag("edge:host/kubernetes", k8sHost), frag("edge:vps/kubernetes", k8sVps));
    }

    static Map<String, List<String>> linksOfServices(Topology t) {
        return t.edges().stream().filter(e -> e.from().startsWith("service:"))
                .collect(Collectors.groupingBy(Edge::from, TreeMap::new,
                        Collectors.mapping(e -> e.kind() + " " + e.to(), Collectors.toList())));
    }

    @Test
    void osOitoQueJaLigavamContinuamLigadosAoMesmoContainer() {
        Map<String, List<String>> l = linksOfServices(TopologyMerger.merge(snapshot(), Sanitizer.DEFAULT, NOW));
        Map<String, String> esperado = Map.of(
                "service:host/fx-panel", "docker:host/fx-panel",
                "service:host/pihole", "docker:host/pihole",
                "service:host/portainer", "docker:host/portainer",
                "service:host/txadmin", "docker:host/fivem",
                "service:vps/9router", "docker:vps/9router",
                "service:vps/freellmapi", "docker:vps/freellmapi",
                "service:vps/homeassistant", "docker:vps/homeassistant",
                "service:vps/portainer", "docker:vps/portainer");
        esperado.forEach((s, c) -> assertEquals(List.of("ROUTES_TO " + c), l.get(s), s));
    }

    @Test
    void classeA_osCincoNodePortsLigamAoWorkloadEAoPodDaPropriaMaquina() {
        Map<String, List<String>> l = linksOfServices(TopologyMerger.merge(snapshot(), Sanitizer.DEFAULT, NOW));
        assertEquals(List.of("ROUTES_TO k8s:host/9router-lab/deployment/9router-lab", "ROUTES_TO k8s:host/9router-lab/pod/9router-lab-5c847d5bdf-tzdmp"),
                l.get("service:host/9router-lab"));
        assertEquals(List.of("ROUTES_TO k8s:host/dozzle/deployment/dozzle", "ROUTES_TO k8s:host/dozzle/pod/dozzle-85d885f8b-wf77r"),
                l.get("service:host/dozzle-k8s"));
        assertEquals(List.of("ROUTES_TO k8s:vps/argocd/deployment/argocd-server", "ROUTES_TO k8s:vps/argocd/pod/argocd-server-5b594474b6-p95sg"),
                l.get("service:vps/argocd"));
        assertEquals(List.of("ROUTES_TO k8s:vps/atlas/deployment/atlas-hub", "ROUTES_TO k8s:vps/atlas/pod/atlas-hub-f95675c74-hbj2d"),
                l.get("service:vps/atlas"));
        assertEquals(List.of("ROUTES_TO k8s:vps/dozzle/deployment/dozzle", "ROUTES_TO k8s:vps/dozzle/pod/dozzle-79df8cd4c9-9pzfc"),
                l.get("service:vps/dozzle-k8s"));
    }

    @Test
    void classeB_clinkEHermesRodamNaMaquinaHost() {
        Map<String, List<String>> l = linksOfServices(TopologyMerger.merge(snapshot(), Sanitizer.DEFAULT, NOW));
        assertEquals(List.of("RUNS_ON machine:host"), l.get("service:host/clink"));
        assertEquals(List.of("RUNS_ON machine:host"), l.get("service:host/hermes"));
    }

    @Test
    void classeC_containerInexistenteViraDriftESemAresta() {
        Topology t = TopologyMerger.merge(snapshot(), Sanitizer.DEFAULT, NOW);
        Map<String, List<String>> l = linksOfServices(t);
        Map<String, List<String>> drift = t.nodes().stream().filter(n -> n.node().kind() == NodeKind.SERVICE)
                .collect(Collectors.toMap(n -> n.node().key(), TopologyNode::drift));
        for (String s : List.of("service:host/dozzle", "service:host/ai-office", "service:vps/dozzle")) {
            assertEquals(null, l.get(s), s);
            assertEquals(List.of("backend-container-inexistente"), drift.get(s), s);
        }
        drift.forEach((s, d) -> {
            if (!List.of("service:host/dozzle", "service:host/ai-office", "service:vps/dozzle").contains(s)) {
                assertEquals(List.of(), d, s);
            }
        });
    }

    @Test
    void contaDeArestasNovas() {
        Topology t = TopologyMerger.merge(snapshot(), Sanitizer.DEFAULT, NOW);
        long servicos = t.edges().stream().filter(e -> e.from().startsWith("service:")).count();
        // 8 containers + 5 x (workload + pod) + 2 processos do host
        assertEquals(8 + 10 + 2, servicos);
        assertTrue(t.nodes().stream().anyMatch(n -> "30080,30443".equals(n.node().attributes().get("nodeports"))),
                "nodeports passa pelo Sanitizer");
    }
}
