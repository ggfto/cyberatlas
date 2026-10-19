package io.github.ggfto.atlas.adapters.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ggfto.atlas.core.LogRequest;
import io.github.ggfto.atlas.core.LogSource;
import java.util.function.Consumer;

/**
 * Logs de container pelo Docker (design §2.2): o id sai da listagem (nunca do inspect) e o follow e o GET de logs
 * da allowlist do {@link DockerClient}. Linhas cruas; quem redige e corta e o EdgeLogs.
 */
public final class DockerLogSource implements LogSource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String machine;
    private final DockerClient client;

    public DockerLogSource(String machine, DockerClient client) {
        this.machine = machine;
        this.client = client;
    }

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public boolean handles(String nodeKey) {
        return nodeKey.startsWith("docker:" + machine + "/");
    }

    @Override
    public AutoCloseable follow(LogRequest req, Consumer<RawLine> out, Runnable onEnd) throws Exception {
        String name = req.nodeKey().substring(("docker:" + machine + "/").length());
        String id = null;
        for (JsonNode c : JSON.readTree(client.get("/containers/json?all=1"))) {
            if (("/" + name).equals(c.path("Names").path(0).asText())) {
                id = c.path("Id").asText();
            }
        }
        if (id == null) {
            throw new IllegalArgumentException("container nao encontrado: " + req.nodeKey());
        }
        DockerLogDemux demux = new DockerLogDemux(out);
        String path = "/containers/" + id + "/logs?follow=" + (req.follow() ? 1 : 0) + "&stdout=1&stderr=1&timestamps=1&tail=" + req.tail();
        return client.stream(path, demux::accept, () -> {
            demux.finish();
            onEnd.run();
        });
    }
}
