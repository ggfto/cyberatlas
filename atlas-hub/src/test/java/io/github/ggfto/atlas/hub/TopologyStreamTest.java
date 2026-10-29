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

    /**
     * Corrida vista na CI (snapshotThenPatchThenResume, "patch" is null): uma mudanca logo depois do inicial nao pode
     * cair antes de o cliente estar inscrito no ao vivo. Aqui sem rede e sem sorte: a mudanca sai no mesmo callback
     * que recebe o inicial, o ponto mais cedo possivel.
     */
    @Test
    void changeRightAfterTheInitialIsNeverLost() throws Exception {
        streams.refresh();
        BlockingQueue<TopologyStream.Initial> got = new LinkedBlockingQueue<>();
        var sub = streams.follow(null).subscribe().with(u -> {
            boolean first = got.isEmpty();
            got.add(u);
            if (first) {
                push("docker:vps/colado-no-inicial");
            }
        });
        try {
            TopologyStream.Initial first = got.poll(10, TimeUnit.SECONDS);
            assertTrue(first instanceof TopologyStream.Initial.Snapshot, String.valueOf(first));
            long v0 = ((TopologyStream.Initial.Snapshot) first).version();
            TopologyStream.Initial next = got.poll(5, TimeUnit.SECONDS);
            assertTrue(next instanceof TopologyStream.Initial.Patch, "patch perdido logo depois do inicial: " + next);
            var patch = ((TopologyStream.Initial.Patch) next).patch();
            assertEquals(v0, patch.base());
            assertTrue(patch.upsertNodes().stream().anyMatch(n -> n.node().key().equals("docker:vps/colado-no-inicial")), patch.toString());
        } finally {
            sub.cancel();
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger WINDOW = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A corrida da CI, forcada: outra thread chama {@code refresh()} exatamente entre o inicial e a inscricao no ao
     * vivo (gancho {@code afterInitial}). A mudanca entra depois do refresh do inicial, entao so pode chegar como
     * patch. Com o lock do follow, a outra thread espera e o patch sai para o cliente ja inscrito; sem ele, o patch
     * sai para ninguem.
     */
    @Test
    void refreshFromAnotherThreadBetweenTheInitialAndTheSubscriptionIsNeverLost() throws Exception {
        streams.refresh();
        String key = "docker:vps/na-janela-" + WINDOW.incrementAndGet();
        TopologyStream real = io.quarkus.arc.ClientProxy.unwrap(streams);
        Thread[] other = new Thread[1];
        real.afterInitial = () -> {
            topology.accept(new Fragment("edge:vps/docker-janela", Instant.now(), Duration.ofSeconds(90),
                    List.of(new Node(key, NodeKind.CONTAINER, key, "vps", Map.of())), List.of(), List.of()));
            other[0] = Thread.ofPlatform().start(real::refresh);
            awaitBlockedOnCurrentThreadOrDone(other[0]);
        };
        BlockingQueue<TopologyStream.Initial> got = new LinkedBlockingQueue<>();
        var sub = streams.follow(null).subscribe().with(got::add);
        try {
            TopologyStream.Initial first = got.poll(10, TimeUnit.SECONDS);
            assertTrue(first instanceof TopologyStream.Initial.Snapshot, String.valueOf(first));
            long v0 = ((TopologyStream.Initial.Snapshot) first).version();
            TopologyStream.Initial next = got.poll(5, TimeUnit.SECONDS);
            assertTrue(next instanceof TopologyStream.Initial.Patch, "patch publicado na janela se perdeu: " + next);
            var patch = ((TopologyStream.Initial.Patch) next).patch();
            assertEquals(v0, patch.base());
            assertTrue(patch.upsertNodes().stream().anyMatch(n -> n.node().key().equals(key)), patch.toString());
        } finally {
            real.afterInitial = () -> {
            };
            sub.cancel();
            other[0].join(5000);
        }
    }

    /**
     * SSE fechado (cancelamento do follow) tira a inscricao dele do ao vivo. O concat antigo cancelava sozinho; no
     * follow, o onTermination do emitter e o unico caminho, e sem ele cada cliente que sai deixa um assinante vivo
     * no BroadcastProcessor. A inscricao deste follow e achada por identidade, entao outros streams abertos nao
     * atrapalham.
     */
    @Test
    void cancelledFollowLeavesNoSubscriberOnTheLiveStream() throws Exception {
        streams.refresh();
        List<?> before = liveSubscribers();
        BlockingQueue<TopologyStream.Initial> got = new LinkedBlockingQueue<>();
        var sub = streams.follow(null).subscribe().with(got::add);
        assertTrue(got.poll(10, TimeUnit.SECONDS) instanceof TopologyStream.Initial.Snapshot);
        List<Object> mine = new ArrayList<>(liveSubscribers());
        mine.removeIf(s -> before.stream().anyMatch(b -> b == s));
        assertEquals(1, mine.size(), "follow devia ter exatamente 1 inscricao no ao vivo");
        sub.cancel();
        assertTrue(liveSubscribers().stream().noneMatch(s -> s == mine.get(0)), "follow cancelado deixou a inscricao viva no ao vivo");
        push("docker:vps/depois-do-cancel");
        assertEquals(null, got.poll(500, TimeUnit.MILLISECONDS), "follow cancelado ainda recebeu patch");
    }

    /** Assinantes atuais do BroadcastProcessor do ao vivo (campo interno do Mutiny; se mudar de nome, o teste quebra alto). */
    private List<?> liveSubscribers() throws Exception {
        Object processor = streams.live();
        java.lang.reflect.Field f = processor.getClass().getDeclaredField("subscribers");
        f.setAccessible(true);
        return List.copyOf((List<?>) ((java.util.concurrent.atomic.AtomicReference<?>) f.get(processor)).get());
    }

    /** Volta quando a thread terminou ou esta bloqueada no monitor que a thread atual segura. */
    private static void awaitBlockedOnCurrentThreadOrDone(Thread t) {
        var mx = java.lang.management.ManagementFactory.getThreadMXBean();
        long me = Thread.currentThread().threadId();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (t.getState() == Thread.State.TERMINATED) {
                return;
            }
            var info = mx.getThreadInfo(t.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockOwnerId() == me) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("a outra thread nem terminou nem esperou o lock");
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
