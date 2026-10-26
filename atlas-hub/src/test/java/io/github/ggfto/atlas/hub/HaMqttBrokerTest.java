package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.*;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.core.buffer.Buffer;
import io.netty.handler.codec.mqtt.MqttQoS;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class HaMqttBrokerTest {
    @Test
    void disabledPublisherDoesNotTouchNetworkOrTopology() {
        HaMqttConfig disabled = (HaMqttConfig) java.lang.reflect.Proxy.newProxyInstance(
                HaMqttConfig.class.getClassLoader(), new Class<?>[] { HaMqttConfig.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("enabled")) return false;
                    throw new AssertionError("Disabled publisher accessed " + method.getName());
                });
        // Null collaborators make any network or topology access fail immediately.
        HaMqttPublisher publisher = new HaMqttPublisher(null, null, disabled);
        assertDoesNotThrow(() -> publisher.start(null));
        assertDoesNotThrow(publisher::stop);
    }

    @Test
    void publisherRetainsDiscoveryStreamsStateExpiresAndSendsWill() throws Exception {
        try (var broker = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                .withExposedPorts(1883).withCommand("mosquitto", "-c", "/test.conf")
                .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(
                        "listener 1883\nallow_anonymous true\n"), "/test.conf")) {
            broker.start();
            var mapping = new io.smallrye.config.SmallRyeConfigBuilder()
                    .withConverter(java.time.Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
                    .withMapping(AtlasConfig.class).withMapping(HaMqttConfig.class)
                    .withDefaultValue("atlas.ha-mqtt.enabled", "true")
                    .withDefaultValue("atlas.ha-mqtt.host", broker.getHost())
                    .withDefaultValue("atlas.ha-mqtt.port", broker.getMappedPort(1883).toString())
                    .withDefaultValue("atlas.ha-mqtt.client-id", "publisher-it")
                    .withDefaultValue("atlas.ha-mqtt.removal-grace", "2s").build();
            var nodes = new java.util.concurrent.atomic.AtomicReference<java.util.List<io.github.ggfto.atlas.core.TopologyNode>>(
                    java.util.List.of(HaDiscoveryTest.node(io.github.ggfto.atlas.core.NodeKind.ROUTE, "initial")));
            var live = io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor
                    .<io.github.ggfto.atlas.core.TopologyPatch>create();
            var stream = new TopologyStream(null, mapping.getConfigMapping(AtlasConfig.class)) {
                @Override public synchronized Initial initial(String id) {
                    return new Initial.Snapshot(1, new io.github.ggfto.atlas.core.Topology(
                            java.time.Instant.now(), nodes.get(), java.util.List.of()));
                }
                @Override public io.smallrye.mutiny.Multi<io.github.ggfto.atlas.core.TopologyPatch> live() { return live; }
            };
            Vertx vertx = Vertx.vertx();
            var publisher = new HaMqttPublisher(vertx, stream, mapping.getConfigMapping(HaMqttConfig.class));
            var watcher = MqttClient.create(vertx);
            var replacement = MqttClient.create(vertx, new MqttClientOptions().setClientId("publisher-it"));
            var received = new LinkedBlockingQueue<io.vertx.mqtt.messages.MqttPublishMessage>();
            var messages = HaDiscovery.messages(nodes.get().getFirst());
            var discovery = messages.stream().filter(m -> m.topic().endsWith("/config")).findFirst().orElseThrow();
            var state = messages.stream().filter(m -> m.topic().endsWith("/state")).findFirst().orElseThrow();
            try {
                publisher.start(null);
                // Wait for initial publication before introducing a fresh subscriber.
                var probe = MqttClient.create(vertx);
                var online = new LinkedBlockingQueue<String>();
                probe.publishHandler(m -> online.add(m.payload().toString()));
                probe.connect(broker.getMappedPort(1883), broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                probe.subscribe("atlas/status", 1).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals("online", online.poll(15, TimeUnit.SECONDS));
                probe.disconnect().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.publishHandler(received::add);
                watcher.connect(broker.getMappedPort(1883), broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.subscribe("#", 1).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                var retained = awaitMessage(received, discovery.topic(), discovery.payload());
                assertTrue(retained.isRetain(), "Fresh subscriber must receive retained discovery");
                awaitMessage(received, state.topic(), state.payload());
                nodes.set(java.util.List.of(HaDiscoveryTest.node(io.github.ggfto.atlas.core.NodeKind.ROUTE, "changed-live")));
                live.onNext(new io.github.ggfto.atlas.core.TopologyPatch(2, 1, java.time.Instant.now(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()));
                awaitMessage(received, state.topic(), HaDiscovery.messages(nodes.get().getFirst()).stream()
                        .filter(m -> m.topic().equals(state.topic())).findFirst().orElseThrow().payload());
                received.clear();
                long removedAt = System.nanoTime();
                nodes.set(java.util.List.of());
                assertNull(received.poll(300, TimeUnit.MILLISECONDS), "Removal must respect grace");
                awaitMessage(received, discovery.topic(), "");
                assertTrue(System.nanoTime() - removedAt >= TimeUnit.SECONDS.toNanos(2),
                        "Discovery must not be deleted before configured grace");
                replacement.connect(broker.getMappedPort(1883), broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                awaitMessage(received, "atlas/status", "offline");
            } finally {
                publisher.stop();
                replacement.disconnect();
                watcher.disconnect();
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static io.vertx.mqtt.messages.MqttPublishMessage awaitMessage(
            LinkedBlockingQueue<io.vertx.mqtt.messages.MqttPublishMessage> queue, String topic, String payload) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var message = queue.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && message.topicName().equals(topic) && message.payload().toString().equals(payload)) return message;
        }
        fail("Missing MQTT message: " + topic + "=" + payload);
        return null;
    }

    @Test
    void retainedDiscoveryAndLastWillSurviveConnectionLoss() throws Exception {
        try (var broker = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                .withExposedPorts(1883).withCommand("mosquitto", "-c", "/mosquitto-no-auth.conf")
                .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(
                        "listener 1883\nallow_anonymous true\n"), "/mosquitto-no-auth.conf")) {
            broker.start();
            Vertx vertx = Vertx.vertx();
            MqttClient watcher = MqttClient.create(vertx);
            MqttClient publisher = MqttClient.create(vertx, new MqttClientOptions()
                    .setClientId("will-test").setWillFlag(true).setWillTopic("atlas/status")
                    .setWillMessage("offline").setWillQoS(1).setWillRetain(true));
            var received = new LinkedBlockingQueue<String>();
            try {
                watcher.publishHandler(message -> received.add(message.topicName() + "=" + message.payload()));
                watcher.connect(broker.getMappedPort(1883), broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                watcher.subscribe("atlas/status", 1).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                publisher.connect(broker.getMappedPort(1883), broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                publisher.publish("atlas/status", Buffer.buffer("online"), MqttQoS.AT_LEAST_ONCE, false, true)
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals("atlas/status=online", received.poll(10, TimeUnit.SECONDS));
                // A second connection with the same ID forces loss without MQTT DISCONNECT.
                MqttClient replacement = MqttClient.create(vertx, new MqttClientOptions().setClientId("will-test"));
                replacement.connect(broker.getMappedPort(1883), broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals("atlas/status=offline", received.poll(10, TimeUnit.SECONDS));
                replacement.disconnect().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            } finally {
                watcher.disconnect();
                vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        }
    }
}
