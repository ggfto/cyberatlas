package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSetListBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentListBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetListBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.github.ggfto.atlas.core.Fragment;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Edge novo com o ClusterRole antigo (antes do bump da base no infra): sem permissao para listar Services a fonte
 * nao cai, so fica sem {@code nodeports}.
 */
@EnableKubernetesMockClient
class KubernetesSourceRbacTest {

    KubernetesClient client;
    KubernetesMockServer server;

    @Test
    void semPermissaoEmServicesPodsContinuamSaindo() {
        server.expect().get().withPath("/api/v1/pods").andReturn(200, new PodListBuilder().addToItems(new PodBuilder().withNewMetadata()
                .withNamespace("dozzle").withName("dozzle-1").addToLabels("app", "dozzle").endMetadata().withNewStatus().withPhase("Running")
                .endStatus().build()).build()).always();
        server.expect().get().withPath("/apis/apps/v1/replicasets").andReturn(200, new ReplicaSetListBuilder().build()).always();
        server.expect().get().withPath("/apis/apps/v1/deployments").andReturn(200, new DeploymentListBuilder().build()).always();
        server.expect().get().withPath("/apis/apps/v1/statefulsets").andReturn(200, new StatefulSetListBuilder().build()).always();
        server.expect().get().withPath("/apis/apps/v1/daemonsets").andReturn(200, new DaemonSetListBuilder().build()).always();
        server.expect().get().withPath("/api/v1/services").andReturn(403, "{\"kind\":\"Status\",\"code\":403,\"reason\":\"Forbidden\"}").always();
        Fragment f = new KubernetesSource("host", client, Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofSeconds(90)).collect();
        assertEquals(1, f.nodes().size());
        assertFalse(f.nodes().get(0).attributes().containsKey("nodeports"));
    }
}
