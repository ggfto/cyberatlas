package io.github.ggfto.atlas.adapters.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.TopologySource;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Containers da maquina pela listagem do Docker ({@code GET /containers/json?all=1}). Da listagem so saem nome,
 * imagem, estado, status e os labels de compose; {@code Command}, os outros labels e qualquer coisa do inspect
 * nunca. O estado vem do {@code State} e da marca de healthcheck no {@code Status}.
 */
public final class DockerSource implements TopologySource {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String machine;
    private final DockerClient client;
    private final Clock clock;
    private final Duration ttl;

    public DockerSource(String machine, DockerClient client, Clock clock, Duration ttl) {
        this.machine = machine;
        this.client = client;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public String name() {
        return "docker";
    }

    @Override
    public Fragment collect() throws IOException {
        JsonNode list = JSON.readTree(client.get("/containers/json?all=1"));
        Instant now = clock.instant();
        List<Node> nodes = new ArrayList<>();
        List<Observation> obs = new ArrayList<>();
        for (JsonNode c : list) {
            String name = c.path("Names").path(0).asText("").replaceFirst("^/", "");
            if (name.isEmpty()) {
                continue;
            }
            String key = "docker:" + machine + "/" + name;
            String state = c.path("State").asText("");
            String status = c.path("Status").asText("");
            String health = health(status);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("image", c.path("Image").asText(""));
            attrs.put("state", state);
            attrs.put("status", status);
            if (health != null) {
                attrs.put("health", health);
            }
            JsonNode labels = c.path("Labels");
            putLabel(attrs, labels, "com.docker.compose.project", "compose_project");
            putLabel(attrs, labels, "com.docker.compose.service", "compose_service");
            nodes.add(new Node(key, NodeKind.CONTAINER, name, machine, attrs));
            obs.add(observe(key, state, status, health, now));
        }
        return new Fragment(name(), now, ttl, nodes, List.of(), obs);
    }

    private Observation observe(String key, String state, String status, String health, Instant now) {
        State s;
        String reason = status;
        switch (state) {
            case "running" -> {
                if ("unhealthy".equals(health)) {
                    s = State.DEGRADED;
                    reason = "unhealthy: " + status;
                } else if ("starting".equals(health)) {
                    s = State.DEGRADED;
                    reason = "health: starting";
                } else {
                    s = State.ONLINE;
                }
            }
            case "restarting" -> {
                s = State.DEGRADED;
                reason = "reiniciando: " + status;
            }
            case "paused" -> {
                s = State.DEGRADED;
                reason = "pausado";
            }
            case "created" -> {
                s = State.OFFLINE;
                reason = "criado, nao iniciado";
            }
            default -> s = State.OFFLINE;
        }
        return new Observation(key, name(), s, reason, now, ttl);
    }

    private static String health(String status) {
        if (status.contains("(unhealthy)")) {
            return "unhealthy";
        }
        if (status.contains("(healthy)")) {
            return "healthy";
        }
        if (status.contains("(health: starting)")) {
            return "starting";
        }
        return null;
    }

    private static void putLabel(Map<String, String> attrs, JsonNode labels, String label, String attr) {
        JsonNode v = labels.path(label);
        if (v.isTextual()) {
            attrs.put(attr, v.asText());
        }
    }
}
