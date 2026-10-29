package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.*;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.NodeStatus;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyNode;
import io.github.ggfto.atlas.core.TopologyPatch;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.messages.MqttPublishMessage;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/** HaMqttPublisher against a real Mosquitto; only TopologyStream is replaced by one the test drives. */
class HaMqttBrokerTest {
    static GenericContainer<?> broker;

    @BeforeAll
    static void startBroker() {
        broker = new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                .withExposedPorts(1883).withCommand("mosquitto", "-c", "/test.conf")
                .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous true\n"), "/test.conf");
        broker.start();
    }

    @AfterAll
    static void stopBroker() {
        broker.stop();
    }

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
    void freshSubscriberGetsRetainedDiscoveryThenLiveStateThenRemovalAfterGrace() throws Exception {
        var stream = new DrivenStream(List.of(HaDiscoveryTest.node(NodeKind.ROUTE, "initial")));
        var messages = HaDiscovery.messages(stream.nodes.get().getFirst());
        var discovery = messages.stream().filter(m -> m.topic().endsWith("/config")).findFirst().orElseThrow();
        var state = messages.stream().filter(m -> m.topic().endsWith("/state")).findFirst().orElseThrow();
        Vertx vertx = Vertx.vertx();
        var publisher = new HaMqttPublisher(vertx, stream, config("publisher-a", "2s"));
        try {
            var status = subscribe(vertx, "atlas/status");
            publisher.start(null);
            awaitLive(status, "atlas/status", "online");
            var watcher = subscribe(vertx, "#");
            assertTrue(await(watcher, discovery.topic(), discovery.payload()).isRetain(),
                    "Fresh subscriber must receive retained discovery");
            await(watcher, state.topic(), state.payload());
            int reads = stream.readers.size();
            TimeUnit.MILLISECONDS.sleep(1500);
            assertEquals(reads, stream.readers.size(), "Publisher must not poll: only live patches read topology");

            // No polling: only the live patch can carry the new state.
            stream.change(List.of(HaDiscoveryTest.node(NodeKind.ROUTE, "changed-live")));
            await(watcher, state.topic(), HaDiscovery.messages(stream.nodes.get().getFirst()).stream()
                    .filter(m -> m.topic().equals(state.topic())).findFirst().orElseThrow().payload());

            watcher.clear();
            long removedAt = System.nanoTime();
            stream.change(List.of());
            assertNull(watcher.poll(300, TimeUnit.MILLISECONDS), "Removal must respect grace");
            await(watcher, discovery.topic(), "");
            assertTrue(System.nanoTime() - removedAt >= TimeUnit.SECONDS.toNanos(2),
                    "Discovery must not be deleted before configured grace");
        } finally {
            publisher.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void largeTopologyIsPublishedWithinTheInflightWindow() throws Exception {
        var nodes = new ArrayList<TopologyNode>();
        for (int i = 0; i < 40; i++) nodes.add(node("route:bulk-" + i + ".example"));
        var stream = new DrivenStream(nodes);
        var expected = new HashSet<String>();
        nodes.forEach(n -> HaDiscovery.messages(n).forEach(m -> expected.add(m.topic())));
        Vertx vertx = Vertx.vertx();
        var publisher = new HaMqttPublisher(vertx, stream, config("publisher-d", "60s"));
        try {
            var watcher = subscribe(vertx, "#");
            publisher.start(null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!expected.isEmpty() && System.nanoTime() < deadline) {
                var message = watcher.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && !message.payload().toString().isEmpty()) expected.remove(message.topicName());
            }
            assertEquals(0, expected.size(), "Topics missing beyond the inflight window of " + HaMqttPublisher.WINDOW);
        } finally {
            publisher.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void brokerRestartWithoutPersistenceGetsDiscoveryAndOnlineAgain() throws Exception {
        int port;
        try (var free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        var stream = new DrivenStream(List.of(HaDiscoveryTest.node(NodeKind.ROUTE, "initial")));
        var messages = HaDiscovery.messages(stream.nodes.get().getFirst());
        var discovery = messages.stream().filter(m -> m.topic().endsWith("/config")).findFirst().orElseThrow();
        var state = messages.stream().filter(m -> m.topic().endsWith("/state")).findFirst().orElseThrow();
        Vertx vertx = Vertx.vertx();
        var publisher = new HaMqttPublisher(vertx, stream, config("publisher-e", "60s", port));
        var first = brokerOn(port);
        first.start();
        try {
            var status = subscribe(vertx, port, "atlas/status");
            publisher.start(null);
            awaitLive(status, "atlas/status", "online");
            // Home Assistant's broker restarting without persistence: every retained message is gone.
            first.stop();
            try (var second = brokerOn(port)) {
                second.start();
                awaitLive(subscribe(vertx, port, "atlas/status"), "atlas/status", "online");
                var fresh = subscribe(vertx, port, "#");
                assertTrue(await(fresh, discovery.topic(), discovery.payload()).isRetain(),
                        "Reconnected publisher must republish retained discovery");
                assertTrue(await(fresh, state.topic(), state.payload()).isRetain());
            }
        } finally {
            publisher.stop();
            first.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Mosquitto without persistence on a fixed host port, so a second one replaces the first at the same address. */
    private static GenericContainer<?> brokerOn(int port) {
        return new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2"))
                .withExposedPorts(1883).withCommand("mosquitto", "-c", "/test.conf")
                .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous true\npersistence false\n"), "/test.conf")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(1883))));
    }

    @Test
    void crashedHubLeavesRetainedWillOffline() throws Exception {
        var stream = new DrivenStream(List.of(HaDiscoveryTest.node(NodeKind.ROUTE, "initial")));
        Vertx hub = Vertx.vertx();
        Vertx vertx = Vertx.vertx();
        try {
            var status = subscribe(vertx, "atlas/status");
            new HaMqttPublisher(hub, stream, config("publisher-b", "60s")).start(null);
            awaitLive(status, "atlas/status", "online");
            // Closing Vert.x drops the socket without MQTT DISCONNECT and without stop(): a crash.
            hub.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            awaitLive(status, "atlas/status", "offline");
            assertTrue(await(subscribe(vertx, "atlas/status"), "atlas/status", "offline").isRetain(),
                    "Home Assistant connecting later must still see the hub offline");
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void liveBurstFromForeignThreadsAndShutdownStayOnOneContext() throws Exception {
        var stream = new DrivenStream(List.of(HaDiscoveryTest.node(NodeKind.ROUTE, "initial")));
        Vertx vertx = Vertx.vertx();
        var failures = Collections.synchronizedList(new ArrayList<Throwable>());
        vertx.exceptionHandler(failures::add);
        var publisher = new HaMqttPublisher(vertx, stream, config("publisher-c", "0s"));
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            var status = subscribe(vertx, "atlas/status");
            publisher.start(null);
            awaitLive(status, "atlas/status", "online");
            var burst = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 200; i++) {
                int n = i;
                burst.add(CompletableFuture.runAsync(() -> stream.change(n % 3 == 0 ? List.of()
                        : List.of(HaDiscoveryTest.node(NodeKind.ROUTE, "burst-" + n))), threads));
                if (i == 150) burst.add(CompletableFuture.runAsync(publisher::stop, threads));
            }
            CompletableFuture.allOf(burst.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            awaitLive(status, "atlas/status", "offline");
            assertEquals(List.of(), failures);
            assertTrue(stream.readers.size() > 1, "Burst must reach the publisher");
            assertFalse(stream.readers.contains(null), "Topology read outside Vert.x");
            assertEquals(1, new HashSet<>(stream.readers).size(), "Publisher state touched from several contexts");
        } finally {
            threads.shutdownNow();
            publisher.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    /** Topology the test changes by hand; each change emits a live patch and every read records its context. */
    static final class DrivenStream extends TopologyStream {
        final AtomicReference<List<TopologyNode>> nodes;
        final List<Context> readers = Collections.synchronizedList(new ArrayList<>());
        private final BroadcastProcessor<TopologyPatch> live = BroadcastProcessor.create();
        private long version = 1;

        DrivenStream(List<TopologyNode> initial) {
            super(null, settings("x", "1s").getConfigMapping(AtlasConfig.class));
            nodes = new AtomicReference<>(initial);
        }

        @Override
        public synchronized Initial initial(String id) {
            readers.add(Vertx.currentContext());
            return new Initial.Snapshot(version, new Topology(Instant.now(), nodes.get(), List.of()));
        }

        @Override
        public Multi<TopologyPatch> live() {
            return live;
        }

        synchronized void change(List<TopologyNode> next) {
            nodes.set(next);
            version++;
            live.onNext(new TopologyPatch(version - 1, version, Instant.now(), List.of(), List.of(), List.of(), List.of()));
        }
    }

    private static TopologyNode node(String key) {
        return new TopologyNode(new Node(key, NodeKind.ROUTE, key, null, Map.of()),
                new NodeStatus(State.ONLINE, "ok", List.of()), List.of());
    }

    static HaMqttConfig config(String clientId, String grace) {
        return config(clientId, grace, broker.getMappedPort(1883));
    }

    static HaMqttConfig config(String clientId, String grace, int port) {
        return settings(clientId, grace, port).getConfigMapping(HaMqttConfig.class);
    }

    private static SmallRyeConfig settings(String clientId, String grace) {
        return settings(clientId, grace, broker.getMappedPort(1883));
    }

    private static SmallRyeConfig settings(String clientId, String grace, int port) {
        return new SmallRyeConfigBuilder()
                .withConverter(Duration.class, 100, new DurationConverter())
                .withMapping(AtlasConfig.class).withMapping(HaMqttConfig.class)
                .withDefaultValue("atlas.ha-mqtt.enabled", "true")
                .withDefaultValue("atlas.ha-mqtt.host", broker.getHost())
                .withDefaultValue("atlas.ha-mqtt.port", String.valueOf(port))
                .withDefaultValue("atlas.ha-mqtt.client-id", clientId)
                .withDefaultValue("atlas.ha-mqtt.removal-grace", grace).build();
    }

    private static LinkedBlockingQueue<MqttPublishMessage> subscribe(Vertx vertx, String topic) throws Exception {
        return subscribe(vertx, broker.getMappedPort(1883), topic);
    }

    private static LinkedBlockingQueue<MqttPublishMessage> subscribe(Vertx vertx, int port, String topic) throws Exception {
        var received = new LinkedBlockingQueue<MqttPublishMessage>();
        var client = MqttClient.create(vertx);
        client.publishHandler(received::add);
        client.connect(port, broker.getHost()).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        client.subscribe(topic, 1).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return received;
    }

    /** Ignores what earlier tests left retained on the broker: only a publication happening now counts. */
    private static void awaitLive(LinkedBlockingQueue<MqttPublishMessage> queue, String topic, String payload)
            throws Exception {
        while (await(queue, topic, payload).isRetain()) {
        }
    }

    private static MqttPublishMessage await(LinkedBlockingQueue<MqttPublishMessage> queue, String topic, String payload)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var message = queue.poll(100, TimeUnit.MILLISECONDS);
            if (message != null && message.topicName().equals(topic) && message.payload().toString().equals(payload)) return message;
        }
        fail("Missing MQTT message: " + topic + "=" + payload);
        return null;
    }
}
