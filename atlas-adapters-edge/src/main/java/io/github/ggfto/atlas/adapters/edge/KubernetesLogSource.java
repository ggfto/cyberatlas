package io.github.ggfto.atlas.adapters.edge;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.github.ggfto.atlas.core.LogRequest;
import io.github.ggfto.atlas.core.LogSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Logs de pod pelo fabric8 ({@code pods/log}, o unico verbo de log do RBAC do edge), com follow/tail/timestamps. */
public final class KubernetesLogSource implements LogSource {

    private final String machine;
    private final KubernetesClient client;

    public KubernetesLogSource(String machine, KubernetesClient client) {
        this.machine = machine;
        this.client = client;
    }

    @Override
    public String name() {
        return "kubernetes";
    }

    @Override
    public boolean handles(String nodeKey) {
        return nodeKey.startsWith("k8s:" + machine + "/") && nodeKey.split("/").length == 4 && "pod".equals(nodeKey.split("/")[2]);
    }

    @Override
    public AutoCloseable follow(LogRequest req, Consumer<RawLine> out, Runnable onEnd) {
        String[] p = req.nodeKey().substring(("k8s:" + machine + "/").length()).split("/");
        var pod = client.pods().inNamespace(p[0]).withName(p[2]);
        LogWatch watch = req.container() == null
                ? pod.usingTimestamps().tailingLines(req.tail()).watchLog()
                : pod.inContainer(req.container()).usingTimestamps().tailingLines(req.tail()).watchLog();
        Thread.ofVirtual().name("k8s-logs").start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(watch.getOutput(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.accept(DockerLogDemux.parse(line, "stdout"));
                }
            } catch (IOException e) {
                // fechado por nos ou pela API: fim
            } finally {
                onEnd.run();
            }
        });
        return watch::close;
    }
}
