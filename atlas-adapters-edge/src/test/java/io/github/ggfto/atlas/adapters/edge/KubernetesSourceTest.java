package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pods e workloads do k3s da maquina (design §1.2), contra o mock server do fabric8 em modo CRUD. */
@EnableKubernetesMockClient(crud = true)
class KubernetesSourceTest {

    KubernetesClient client;
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    static Pod pod(String ns, String name, String phase, boolean ready, int restarts, String waitingReason, String rs) {
        var status = new ContainerStatusBuilder().withName("app").withReady(ready).withRestartCount(restarts).withImage("ghcr.io/x/app:1");
        if (waitingReason != null) {
            status.withNewState().withNewWaiting().withReason(waitingReason).endWaiting().endState();
        }
        var b = new PodBuilder().withNewMetadata().withNamespace(ns).withName(name);
        if (rs != null) {
            b = b.addNewOwnerReference().withKind("ReplicaSet").withName(rs).withApiVersion("apps/v1").withUid("u-" + rs).endOwnerReference();
        }
        return b.addToAnnotations("segredo/token", "nao-sai").endMetadata()
                .withNewSpec().addNewContainer().withName("app").withImage("ghcr.io/x/app:1").addNewEnv().withName("API_KEY").withValue("segredo").endEnv()
                .endContainer().endSpec()
                .withNewStatus().withPhase(phase).withContainerStatuses(status.build()).endStatus().build();
    }

    @BeforeEach
    void cluster() {
        client.apps().deployments().inNamespace("dozzle").resource(new DeploymentBuilder().withNewMetadata().withNamespace("dozzle").withName("dozzle")
                .endMetadata().withNewSpec().withReplicas(1).endSpec().withNewStatus().withReadyReplicas(1).withReplicas(1).endStatus().build()).create();
        client.apps().deployments().inNamespace("lab").resource(new DeploymentBuilder().withNewMetadata().withNamespace("lab").withName("9router-lab")
                .endMetadata().withNewSpec().withReplicas(1).endSpec().withNewStatus().withReplicas(1).endStatus().build()).create();
        client.apps().replicaSets().inNamespace("dozzle").resource(new ReplicaSetBuilder().withNewMetadata().withNamespace("dozzle").withName("dozzle-7d9")
                .addNewOwnerReference().withKind("Deployment").withName("dozzle").withApiVersion("apps/v1").withUid("u-d").endOwnerReference()
                .endMetadata().build()).create();
        client.pods().inNamespace("dozzle").resource(pod("dozzle", "dozzle-7d9-abc", "Running", true, 0, null, "dozzle-7d9")).create();
        client.pods().inNamespace("lab").resource(pod("lab", "9router-lab-1", "Running", false, 7, "CrashLoopBackOff", null)).create();
        client.pods().inNamespace("lab").resource(pod("lab", "pendente", "Pending", false, 0, null, null)).create();
        client.pods().inNamespace("lab").resource(pod("lab", "falhou", "Failed", false, 0, null, null)).create();
    }

    Fragment collect() throws Exception {
        return new KubernetesSource("host", client, CLOCK, Duration.ofSeconds(90)).collect();
    }

    @Test
    void podStateFollowsPhaseReadinessAndWaitingReason() throws Exception {
        Map<String, Observation> o = collect().observations().stream().collect(Collectors.toMap(Observation::nodeKey, x -> x));
        assertEquals(State.ONLINE, o.get("k8s:host/dozzle/pod/dozzle-7d9-abc").state());
        assertEquals(State.DEGRADED, o.get("k8s:host/lab/pod/9router-lab-1").state());
        assertEquals("CrashLoopBackOff (7 restarts)", o.get("k8s:host/lab/pod/9router-lab-1").reason());
        assertEquals(State.DEGRADED, o.get("k8s:host/lab/pod/pendente").state());
        assertEquals(State.OFFLINE, o.get("k8s:host/lab/pod/falhou").state());
    }

    @Test
    void workloadStateFollowsReadyReplicas() throws Exception {
        Map<String, Observation> o = collect().observations().stream().collect(Collectors.toMap(Observation::nodeKey, x -> x));
        assertEquals(State.ONLINE, o.get("k8s:host/dozzle/deployment/dozzle").state());
        assertEquals(State.OFFLINE, o.get("k8s:host/lab/deployment/9router-lab").state());
        assertEquals("0/1 prontos", o.get("k8s:host/lab/deployment/9router-lab").reason());
    }

    @Test
    void podLinksToItsDeploymentThroughTheReplicaSet() throws Exception {
        assertTrue(collect().edges().contains(Edge.of("k8s:host/dozzle/deployment/dozzle", "k8s:host/dozzle/pod/dozzle-7d9-abc", EdgeKind.RUNS_ON)));
    }

    @Test
    void attributesAreStatusOnlyNeverEnvOrAnnotations() throws Exception {
        Fragment f = collect();
        Node p = f.nodes().stream().filter(n -> n.key().equals("k8s:host/dozzle/pod/dozzle-7d9-abc")).findFirst().orElseThrow();
        assertEquals(Map.of("namespace", "dozzle", "phase", "Running", "ready", "1/1", "restarts", "0",
                "owner", "Deployment/dozzle", "image", "ghcr.io/x/app:1"), p.attributes());
        assertFalse(f.toString().contains("segredo"), "env/annotation nunca entram");
    }
}
