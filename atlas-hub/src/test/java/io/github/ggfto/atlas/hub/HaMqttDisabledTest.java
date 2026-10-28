package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Padrao (atlas.ha-mqtt.enabled ausente): o hub sobe e nunca abre conexao, mesmo com host e porta apontados. */
@QuarkusTest
@TestProfile(HaMqttDisabledTest.Profile.class)
class HaMqttDisabledTest {

    public static class Profile extends FixtureProfile {
        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(ListeningSocket.class));
        }
    }

    /** Porta que so aceita e conta: qualquer tentativa do publisher vira um accept. */
    public static class ListeningSocket implements QuarkusTestResourceLifecycleManager {
        static final AtomicInteger ACCEPTED = new AtomicInteger();
        private ServerSocket socket;

        @Override
        public Map<String, String> start() {
            try {
                socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            Thread.ofVirtual().start(() -> {
                while (!socket.isClosed()) {
                    try (var ignored = socket.accept()) {
                        ACCEPTED.incrementAndGet();
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
            return Map.of("atlas.ha-mqtt.host", "127.0.0.1", "atlas.ha-mqtt.port", String.valueOf(socket.getLocalPort()));
        }

        @Override
        public void stop() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Inject
    HaMqttConfig config;

    @Test
    void hubBootsWithMqttOffAndNeverConnects() throws Exception {
        // o publisher tentaria na subida e de novo a cada 1 s: 2,5 s cobrem as duas
        TimeUnit.MILLISECONDS.sleep(2500);
        assertEquals(0, ListeningSocket.ACCEPTED.get(), "Hub opened a connection with MQTT off");
        assertFalse(config.enabled());
    }
}
