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
