package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.TopologyNode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.netty.handler.codec.mqtt.MqttQoS;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Optional output only: never contributes observations to topology. */
@ApplicationScoped
public class HaMqttPublisher {
    private final Vertx vertx;
    private final TopologyStream stream;
    private final HaMqttConfig config;
    private final Map<String, Long> missing = new HashMap<>();
    private final Set<String> known = new HashSet<>();
    private MqttClient client;
    private Cancellable subscription;
    private long timer = -1;
    private final Map<String, String> sent = new HashMap<>();
    private boolean connecting;
    private boolean stopped;

    HaMqttPublisher(Vertx vertx, TopologyStream stream, HaMqttConfig config) {
        this.vertx = vertx;
        this.stream = stream;
        this.config = config;
    }

    void start(@Observes StartupEvent event) {
        if (!config.enabled()) return;
        MqttClientOptions options = new MqttClientOptions().setClientId(config.clientId())
                .setSsl(config.tls()).setWillFlag(true).setWillTopic("atlas/status")
                .setWillMessage("offline").setWillQoS(1).setWillRetain(true);
        config.username().ifPresent(options::setUsername);
        config.password().ifPresent(options::setPassword);
        client = MqttClient.create(vertx, options);
        subscription = stream.live().subscribe().with(patch -> vertx.runOnContext(ignored -> snapshot()));
        timer = vertx.setPeriodic(1000, ignored -> {
            if (client.isConnected()) snapshot();
            else connect();
        });
        vertx.runOnContext(ignored -> connect());
    }

    private void connect() {
        if (stopped || connecting || client.isConnected()) return;
        connecting = true;
        client.connect(config.port(), config.host()).onComplete(result -> {
            connecting = false;
            if (result.succeeded() && !stopped) {
                sent.clear();
                snapshot();
                publish(new HaDiscovery.Message("atlas/status", "online", true));
            }
        });
    }

    private void snapshot() {
        if (stopped || !client.isConnected()) return;
        if (!(stream.initial(null) instanceof TopologyStream.Initial.Snapshot snapshot)) return;
        Set<String> present = new HashSet<>();
        for (TopologyNode node : snapshot.topology().nodes()) {
            var messages = HaDiscovery.messages(node);
            if (messages.isEmpty()) continue;
            present.add(node.node().key());
            messages.forEach(this::publish);
        }
        long now = System.nanoTime();
        for (String key : known) {
            if (present.contains(key)) missing.remove(key);
            else missing.putIfAbsent(key, now);
        }
        var iterator = missing.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue() >= config.removalGrace().toNanos()) {
                HaDiscovery.remove(entry.getKey()).forEach(this::publish);
                known.remove(entry.getKey());
                iterator.remove();
            }
        }
        known.addAll(present);
    }

    private void publish(HaDiscovery.Message message) {
        if (message.payload().equals(sent.get(message.topic()))) return;
        sent.put(message.topic(), message.payload());
        client.publish(message.topic(), Buffer.buffer(message.payload()), MqttQoS.AT_LEAST_ONCE,
                false, message.retain()).onFailure(failure -> {
                    sent.remove(message.topic());
                    client.disconnect();
                });
    }

    @PreDestroy
    void stop() {
        stopped = true;
        if (subscription != null) subscription.cancel();
        if (timer != -1) vertx.cancelTimer(timer);
        if (client != null && client.isConnected()) {
            client.publish("atlas/status", Buffer.buffer("offline"), MqttQoS.AT_LEAST_ONCE, false, true)
                    .onComplete(ignored -> client.disconnect());
        }
    }
}
