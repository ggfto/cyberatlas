package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.TopologyNode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import io.vertx.mqtt.MqttClientOptions;
import io.netty.handler.codec.mqtt.MqttQoS;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Optional output only: never contributes observations to topology. All mutable state below is confined to one
 * Vert.x context: live patches, the timer, MQTT completions and stop() hop onto it instead of sharing locks.
 */
@ApplicationScoped
public class HaMqttPublisher {
    private static final Logger LOG = Logger.getLogger(HaMqttPublisher.class);
    /** QoS 1 messages awaiting PUBACK: the client fails any publish past its inflight limit. */
    static final int WINDOW = 10;
    private final Vertx vertx;
    private final TopologyStream stream;
    private final HaMqttConfig config;
    private final Map<String, Long> missing = new HashMap<>();
    private final Set<String> known = new HashSet<>();
    private final Map<String, String> sent = new HashMap<>();
    private final Deque<HaDiscovery.Message> pending = new ArrayDeque<>();
    private int inflight;
    private volatile Context context;
    private MqttClient client;
    private Cancellable subscription;
    private long timer = -1;
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
                .setWillMessage("offline").setWillQoS(1).setWillRetain(true)
                // one slot above the window stays free for the graceful offline in stop()
                .setMaxInflightQueue(WINDOW + 1);
        config.username().ifPresent(options::setUsername);
        config.password().ifPresent(options::setPassword);
        Context owner = vertx.getOrCreateContext();
        context = owner;
        owner.runOnContext(ignored -> {
            client = MqttClient.create(vertx, options);
            client.publishCompletionHandler(id -> acknowledged());
            client.publishCompletionExpirationHandler(id -> acknowledged());
            // Live patches drive publication; the timer only reconnects and expires the grace.
            subscription = stream.live().subscribe().with(patch -> owner.runOnContext(next -> snapshot()));
            timer = vertx.setPeriodic(1000, tick -> tick());
            connect();
        });
    }

    private void tick() {
        if (stopped) return;
        if (!client.isConnected()) {
            connect();
            return;
        }
        long now = System.nanoTime();
        var iterator = missing.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue() >= config.removalGrace().toNanos()) {
                HaDiscovery.remove(entry.getKey()).forEach(this::publish);
                known.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    private void connect() {
        if (stopped || connecting || client.isConnected()) return;
        connecting = true;
        client.connect(config.port(), config.host()).onComplete(result -> {
            connecting = false;
            if (result.failed()) {
                LOG.debugf("MQTT %s:%d unavailable: %s", config.host(), config.port(), result.cause().getMessage());
            } else if (!stopped) {
                sent.clear();
                pending.clear();
                inflight = 0;
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
        known.addAll(present);
    }

    private void publish(HaDiscovery.Message message) {
        if (message.payload().equals(sent.get(message.topic()))) return;
        sent.put(message.topic(), message.payload());
        pending.add(message);
        drain();
    }

    private void drain() {
        while (inflight < WINDOW && !pending.isEmpty() && client.isConnected()) {
            var message = pending.poll();
            inflight++;
            client.publish(message.topic(), Buffer.buffer(message.payload()), MqttQoS.AT_LEAST_ONCE,
                    false, message.retain()).onFailure(failure -> {
                        sent.remove(message.topic());
                        client.disconnect();
                    });
        }
    }

    private void acknowledged() {
        inflight = Math.max(0, inflight - 1);
        drain();
    }

    @PreDestroy
    void stop() {
        Context owner = context;
        if (owner == null) return;
        var done = new CompletableFuture<Void>();
        owner.runOnContext(ignored -> {
            stopped = true;
            pending.clear();
            if (subscription != null) subscription.cancel();
            vertx.cancelTimer(timer);
            if (!client.isConnected()) {
                done.complete(null);
                return;
            }
            client.publish("atlas/status", Buffer.buffer("offline"), MqttQoS.AT_LEAST_ONCE, false, true)
                    .onComplete(published -> client.disconnect().onComplete(closed -> done.complete(null)));
        });
        try {
            done.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The will still marks the hub offline if the broker never saw the graceful message.
            LOG.debugf("MQTT stop did not confirm offline: %s", e.toString());
        }
    }
}
