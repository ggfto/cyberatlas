package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.messages.MqttPublishMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/** Binario (nativo no CI) com o MQTT ligado contra um Mosquitto: discovery retido da fixture e o hub online. */
@QuarkusIntegrationTest
@TestProfile(HaMqttIT.Profile.class)
class HaMqttIT {

    public static class Profile extends FixtureProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new HashMap<>(super.getConfigOverrides());
            config.put("atlas.ha-mqtt.enabled", "true");
            config.put("atlas.ha-mqtt.client-id", "atlas-hub-it");
            return config;
        }

        @Override
        public List<QuarkusTestProfile.TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(Mosquitto.class));
        }
    }

    public static class Mosquitto implements QuarkusTestResourceLifecycleManager {
        static GenericContainer<?> broker;

        @Override
        public Map<String, String> start() {
            broker = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                    .withExposedPorts(1883).withCommand("mosquitto", "-c", "/test.conf")
                    .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous true\n"), "/test.conf");
            broker.start();
            return Map.of("atlas.ha-mqtt.host", broker.getHost(), "atlas.ha-mqtt.port", broker.getMappedPort(1883).toString());
        }

        @Override
        public void stop() {
            broker.stop();
        }
    }

    @Test
    void hubPublishesRetainedDiscoveryAndOnline() throws Exception {
        String connectivity = "homeassistant/binary_sensor/atlas/" + HaDiscovery.objectId("route:ha.gf2.in") + "_connectivity/config";
        Vertx vertx = Vertx.vertx();
        try {
            var received = new LinkedBlockingQueue<MqttPublishMessage>();
            var client = MqttClient.create(vertx);
            client.publishHandler(received::add);
            client.connect(Mosquitto.broker.getMappedPort(1883), Mosquitto.broker.getHost())
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            client.subscribe("#", 1).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            boolean online = false, discovery = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!(online && discovery) && System.nanoTime() < deadline) {
                var m = received.poll(100, TimeUnit.MILLISECONDS);
                if (m == null) continue;
                online |= m.topicName().equals("atlas/status") && m.payload().toString().equals("online");
                discovery |= m.topicName().equals(connectivity) && m.isRetain()
                        && m.payload().toString().contains("\"availability\"");
            }
            assertTrue(online && discovery, "online=" + online + " discovery=" + discovery);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
