package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.github.ggfto.atlas.core.LogRequest;
import io.github.ggfto.atlas.core.LogSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Logs de pod pelo fabric8 (pods/log com follow, tail e timestamps), contra o mock server. */
@EnableKubernetesMockClient
class KubernetesLogSourceTest {

    KubernetesClient client;
    KubernetesMockServer server;

    @Test
    void followsThePodLog() throws Exception {
        server.expect().get().withPath("/api/v1/namespaces/lab/pods/9router-lab-1/log?pretty=false&tailLines=50&timestamps=true&follow=true")
                .andReturn(200, "2026-10-19T12:00:00.5Z subindo\n2026-10-19T12:00:01Z ok\n").always();
        KubernetesLogSource src = new KubernetesLogSource("host", client);
        assertTrue(src.handles("k8s:host/lab/pod/9router-lab-1"));
        assertFalse(src.handles("k8s:host/lab/deployment/9router-lab"));
        assertFalse(src.handles("k8s:vps/lab/pod/x"));
        List<LogSource.RawLine> lines = new CopyOnWriteArrayList<>();
        CountDownLatch end = new CountDownLatch(1);
        try (AutoCloseable h = src.follow(new LogRequest("k8s:host/lab/pod/9router-lab-1", null, 50, true), lines::add, end::countDown)) {
            assertTrue(end.await(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(new LogSource.RawLine(Instant.parse("2026-10-19T12:00:00.5Z"), "stdout", "subindo"),
                new LogSource.RawLine(Instant.parse("2026-10-19T12:00:01Z"), "stdout", "ok")), lines);
    }
}
