package io.github.ggfto.atlas.adapters.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Argo CD com conta so leitura (design §1.3): sync e health por app, e quem ela gerencia. */
class ArgoSourceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    static final String TOKEN = "argo-token-de-teste";

    static final String APPS = """
            {"items": [
              {"metadata": {"name": "host-9router-lab", "namespace": "argocd"},
               "spec": {"project": "default", "destination": {"server": "https://10.99.0.2:6443", "namespace": "lab"}},
               "status": {"sync": {"status": "OutOfSync", "revision": "deadbeef"}, "health": {"status": "Healthy"},
                          "resources": [{"group": "apps", "kind": "Deployment", "namespace": "lab", "name": "9router-lab"},
                                        {"kind": "Service", "namespace": "lab", "name": "9router-lab"}]}},
              {"metadata": {"name": "vps-dozzle"},
               "spec": {"destination": {"server": "https://kubernetes.default.svc", "namespace": "dozzle"}},
               "status": {"sync": {"status": "Synced"}, "health": {"status": "Healthy"},
                          "resources": [{"group": "apps", "kind": "Deployment", "namespace": "dozzle", "name": "dozzle"},
                                        {"group": "apps", "kind": "StatefulSet", "namespace": "dozzle", "name": "dozzle-db"}]}},
              {"metadata": {"name": "quebrada"},
               "spec": {"destination": {"name": "desconhecido", "namespace": "x"}},
               "status": {"sync": {"status": "Synced"}, "health": {"status": "Degraded"},
                          "resources": [{"group": "apps", "kind": "Deployment", "namespace": "x", "name": "x"}]}},
              {"metadata": {"name": "faltando"},
               "spec": {"destination": {"name": "in-cluster", "namespace": "y"}},
               "status": {"sync": {"status": "Synced"}, "health": {"status": "Missing"}}}
            ]}
            """;

    static ArgoSource source(FakeApi f) {
        return new ArgoSource(f.base(), TOKEN, Map.of("https://10.99.0.2:6443", "host", "https://kubernetes.default.svc", "vps",
                "in-cluster", "vps"), Duration.ofSeconds(2), CLOCK, Duration.ofSeconds(90));
    }

    @Test
    void appsBecomeNodesWithSyncAndHealth() throws Exception {
        try (FakeApi f = new FakeApi(Map.of("/api/v1/applications", new FakeApi.Reply(200, APPS)))) {
            Fragment frag = source(f).collect();
            Map<String, Node> n = frag.nodes().stream().collect(Collectors.toMap(Node::key, x -> x));
            assertEquals(Map.of("sync", "OutOfSync", "health", "Healthy", "namespace", "lab"), n.get("argo:host-9router-lab").attributes());
            assertEquals("host", n.get("argo:host-9router-lab").machine());
            Map<String, Observation> o = frag.observations().stream().collect(Collectors.toMap(Observation::nodeKey, x -> x));
            assertEquals(State.DEGRADED, o.get("argo:host-9router-lab").state());
            assertEquals("OutOfSync/Healthy", o.get("argo:host-9router-lab").reason());
            assertEquals(State.ONLINE, o.get("argo:vps-dozzle").state());
            assertEquals(State.DEGRADED, o.get("argo:quebrada").state());
            assertEquals(State.OFFLINE, o.get("argo:faltando").state());
            assertEquals("argocd", frag.source());
        }
    }

    @Test
    void appManagesItsWorkloadsOnTheMappedMachine() throws Exception {
        try (FakeApi f = new FakeApi(Map.of("/api/v1/applications", new FakeApi.Reply(200, APPS)))) {
            Fragment frag = source(f).collect();
            assertTrue(frag.edges().contains(Edge.of("argo:host-9router-lab", "k8s:host/lab/deployment/9router-lab", EdgeKind.MANAGES)));
            assertTrue(frag.edges().contains(Edge.of("argo:vps-dozzle", "k8s:vps/dozzle/statefulset/dozzle-db", EdgeKind.MANAGES)));
            assertFalse(frag.edges().stream().anyMatch(e -> e.to().contains("/service/")), "so workloads");
            assertFalse(frag.edges().stream().anyMatch(e -> e.from().equals("argo:quebrada")), "cluster sem maquina, sem aresta");
        }
    }

    @Test
    void clusterUrlTokenAndRevisionNeverReachTheNodes() throws Exception {
        try (FakeApi f = new FakeApi(Map.of("/api/v1/applications", new FakeApi.Reply(200, APPS)))) {
            String all = source(f).collect().toString();
            for (String leak : new String[] {"10.99.0.2", "6443", "kubernetes.default.svc", "deadbeef", TOKEN}) {
                assertFalse(all.contains(leak), "vazou " + leak);
            }
            assertEquals("Bearer " + TOKEN, f.auth.get(0));
        }
    }

    @Test
    void unauthorizedAndTimeoutAreErrors() throws Exception {
        try (FakeApi f = new FakeApi(Map.of("/api/v1/applications", new FakeApi.Reply(401, "{\"error\":\"invalid session\"}")))) {
            IOException e = assertThrows(IOException.class, () -> source(f).collect());
            assertEquals("argocd GET /api/v1/applications: HTTP 401", e.getMessage());
        }
        try (FakeApi f = new FakeApi(Map.of("/api/v1/applications", new FakeApi.Reply(200, APPS, 5000)))) {
            assertEquals("argocd GET /api/v1/applications: timeout", assertThrows(IOException.class, () -> source(f).collect()).getMessage());
        }
    }
}
