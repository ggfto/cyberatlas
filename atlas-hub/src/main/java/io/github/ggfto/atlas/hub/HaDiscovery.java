package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.*;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Pure mapping. Never exports source keys, machine identifiers or source observations. */
public final class HaDiscovery {
    private HaDiscovery() {}
    private static final Set<NodeKind> ALLOWED = Set.of(NodeKind.ROUTE, NodeKind.SERVICE,
            NodeKind.MACHINE, NodeKind.TUNNEL, NodeKind.ARGO);
    public record Message(String topic, String payload, boolean retain) {}
    private static final List<String> SUFFIXES = List.of("connectivity", "state", "problem");

    public static String objectId(String key) {
        try {
            return "node_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : Redactor.DEFAULT.redact(Sanitizer.maskIpv4(value));
    }

    private static String configTopic(String id, String suffix) {
        return "homeassistant/" + (suffix.equals("state") ? "sensor" : "binary_sensor")
                + "/atlas/" + id + "_" + suffix + "/config";
    }

    public static List<Message> messages(TopologyNode topologyNode) {
        Node node = Sanitizer.DEFAULT.sanitize(topologyNode.node());
        if (!ALLOWED.contains(node.kind())) return List.of();
        String id = objectId(node.key());
        String stateTopic = "atlas/node/" + id + "/state";
        List<Message> result = new ArrayList<>();
        for (String suffix : SUFFIXES) {
            JsonObject config = new JsonObject()
                    .put("name", clean(node.name()) + " " + suffix)
                    .put("unique_id", "atlas_" + id + "_" + suffix)
                    .put("state_topic", stateTopic)
                    .put("value_template", "{{ value_json." + suffix + " }}")
                    .put("availability", List.of(Map.of("topic", "atlas/status", "payload_available", "online",
                            "payload_not_available", "offline")));
            if (suffix.equals("state")) config.put("json_attributes_topic", stateTopic);
            else config.put("device_class", suffix).put("payload_on", "ON").put("payload_off", "OFF");
            result.add(new Message(configTopic(id, suffix), config.encode(), true));
        }
        State state = topologyNode.status() == null ? State.UNKNOWN : topologyNode.status().state();
        JsonObject attributes = new JsonObject();
        node.attributes().forEach((key, value) -> attributes.put(key, clean(value)));
        JsonObject payload = new JsonObject().put("state", state.name().toLowerCase(Locale.ROOT))
                .put("connectivity", state == State.UNKNOWN ? "unknown" :
                        state == State.OFFLINE ? "OFF" : "ON")
                .put("problem", topologyNode.drift().isEmpty() ? "OFF" : "ON")
                .put("reason", topologyNode.status() == null ? "" : clean(topologyNode.status().reason()))
                .put("badges", topologyNode.drift().stream().map(HaDiscovery::clean).toList())
                .put("attributes", attributes);
        result.add(new Message(stateTopic, payload.encode(), true));
        return List.copyOf(result);
    }

    public static List<Message> remove(String key) {
        String id = objectId(key);
        return SUFFIXES.stream().map(suffix -> new Message(configTopic(id, suffix), "", true)).toList();
    }
}
