package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** SSE /api/topology/stream (design §3.4): snapshot ao conectar, patch a cada mudanca, retomada por Last-Event-ID. */
@QuarkusTest
@TestProfile(FixtureProfile.class)
public class TopologyStreamTest {

    public record Event(String name, String id, String data) {
    }

    @TestHTTPResource("/api/topology/stream")
    URI stream;

    @Inject
    TopologyService topology;

    @Inject
    TopologyStream streams;

    public static BlockingQueue<Event> open(URI uri, String lastEventId) {
        return open(uri, lastEventId, Tokens.viewer());
    }

    public static BlockingQueue<Event> open(URI uri, String lastEventId, String token) {
        BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).header("Accept", "text/event-stream").header("Authorization", "Bearer " + token);
        if (lastEventId != null) {
            b.header("Last-Event-ID", lastEventId);
        }
        HttpClient.newHttpClient().sendAsync(b.build(), HttpResponse.BodyHandlers.ofLines()).thenAccept(resp -> {
            List<String> block = new ArrayList<>();
            resp.body().forEach(line -> {
                if (!line.isEmpty()) {
                    block.add(line);
                    return;
                }
                String name = null;
                String id = null;
                StringBuilder data = new StringBuilder();
                for (String l : block) {
                    if (l.startsWith("event:")) {
                        name = l.substring(6).trim();
                    } else if (l.startsWith("id:")) {
                        id = l.substring(3).trim();
                    } else if (l.startsWith("data:")) {
                        data.append(l.substring(5).trim());
                    }
                }
                if (name != null) {
                    events.add(new Event(name, id, data.toString()));
                }
                block.clear();
            });
        });
        return events;
    }

    void push(String key) {
        topology.accept(new Fragment("edge:vps/docker", Instant.now(), Duration.ofSeconds(90),
                List.of(new Node(key, NodeKind.CONTAINER, key, "vps", Map.of())), List.of(), List.of()));
        streams.refresh();
    }

    @Test
    void snapshotThenPatchThenResume() throws Exception {
        streams.refresh();
        BlockingQueue<Event> live = open(stream, null);
        Event first = live.poll(10, TimeUnit.SECONDS);
        assertEquals("snapshot", first.name());
        assertTrue(first.data().contains("\"route:ha.gf2.in\""), first.data());
        long v0 = Long.parseLong(first.id());

        push("docker:vps/novo-1");
        Event patch = live.poll(10, TimeUnit.SECONDS);
        assertEquals("patch", patch.name());
        assertEquals(v0 + 1, Long.parseLong(patch.id()));
        assertTrue(patch.data().contains("docker:vps/novo-1"), patch.data());
        assertTrue(patch.data().contains("\"base\":" + v0), patch.data());

        // reconecta de onde parou: so o que mudou desde v0, nao o mapa inteiro
        Event resumed = open(stream, String.valueOf(v0)).poll(10, TimeUnit.SECONDS);
        assertEquals("patch", resumed.name());
        assertTrue(resumed.data().contains("docker:vps/novo-1"));
        assertTrue(!resumed.data().contains("\"route:ha.gf2.in\""), "retomada nao reenvia o mapa inteiro");
    }

    @Test
    void unknownOrTooOldIdGetsAFreshSnapshot() throws Exception {
        streams.refresh();
        assertEquals("snapshot", open(stream, "99999999").poll(10, TimeUnit.SECONDS).name());
        assertEquals("snapshot", open(stream, "nao-e-numero").poll(10, TimeUnit.SECONDS).name());
    }

    @Test
    void unchangedMapEmitsNoPatch() throws Exception {
        streams.refresh();
        BlockingQueue<Event> live = open(stream, null);
        live.poll(10, TimeUnit.SECONDS);
        streams.refresh();
        streams.refresh();
        assertEquals(null, live.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void newClientGetsTheLatestMapEvenBeforeTheNextTick() throws Exception {
        // corrida vista na CI: o tick do stream rodou antes da primeira coleta e o cliente recebeu o mapa vazio
        streams.refresh();
        topology.accept(new Fragment("edge:vps/docker-recente", Instant.now(), Duration.ofSeconds(90),
                List.of(new Node("docker:vps/sem-tick", NodeKind.CONTAINER, "sem-tick", "vps", Map.of())), List.of(), List.of()));
        Event first = open(stream, null).poll(10, TimeUnit.SECONDS);
        assertEquals("snapshot", first.name());
        assertTrue(first.data().contains("docker:vps/sem-tick"), "cliente novo viu o mapa velho");
    }
}
