package io.github.ggfto.atlas.hub.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.LogLine;
import io.github.ggfto.atlas.core.Redactor;
import io.github.ggfto.atlas.protocol.Frame;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fan-out de log no hub (design §2.4): um follow por alvo no edge, buffer por assinante (o lento perde as mais
 * antigas e recebe dropped, sem frear ninguem), filtro RE2J por assinante, redacao de novo no hub e limites.
 */
class LogHubTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");
    final List<Frame> toEdge = new CopyOnWriteArrayList<>();
    final LogHub hub = new LogHub((edgeId, f) -> toEdge.add(f), Redactor.DEFAULT,
            new LogHub.Limits(2, 3, 5, Duration.ofMillis(200), Duration.ofSeconds(30), 4));

    @AfterEach
    void stop() {
        hub.close();
    }

    List<Frame.LogOpen> opens() {
        return toEdge.stream().filter(f -> f instanceof Frame.LogOpen).map(f -> (Frame.LogOpen) f).toList();
    }

    static LogLine line(String text) {
        return new LogLine(AT, "stdout", text, false);
    }

    void edgeSends(String streamId, long seq, long dropped, String... texts) {
        hub.fromEdge("edge-vps", new Frame.LogLines(streamId, seq, java.util.Arrays.stream(texts).map(LogHubTest::line).toList(), dropped));
    }

    static List<String> texts(AssertSubscriber<LogHub.LogEvent> s) {
        return s.getItems().stream().filter(e -> e instanceof LogHub.LogEvent.Line).map(e -> ((LogHub.LogEvent.Line) e).line().text()).toList();
    }

    @Test
    void oneFollowOnTheEdgeForManySubscribersEachWithItsFilter() {
        var all = hub.open("edge-vps", "docker:vps/9router", null, 100, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(100));
        var erros = hub.open("edge-vps", "docker:vps/9router", null, 100, "ERRO|panic", true, "b").subscribe().withSubscriber(AssertSubscriber.create(100));
        assertEquals(1, opens().size(), "um follow so no edge");
        assertEquals(new Frame.Credit(opens().get(0).streamId(), 4), toEdge.get(1), "credito inicial");
        edgeSends(opens().get(0).streamId(), 1, 0, "ok 1", "ERRO no banco", "ok 2", "panic: x");
        assertEquals(List.of("ok 1", "ERRO no banco", "ok 2", "panic: x"), texts(all));
        assertEquals(List.of("ERRO no banco", "panic: x"), texts(erros));
        assertEquals(new Frame.Credit(opens().get(0).streamId(), 1), toEdge.get(toEdge.size() - 1), "lote despachado devolve credito");
    }

    @Test
    void slowSubscriberLosesTheOldestWithoutHoldingTheOthers() {
        var lento = hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(0));
        var rapido = hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "b").subscribe().withSubscriber(AssertSubscriber.create(1000));
        String id = opens().get(0).streamId();
        edgeSends(id, 1, 0, IntStream.range(0, 8).mapToObj(i -> "l" + i).toArray(String[]::new));
        assertEquals(8, texts(rapido).size());
        lento.request(100);
        // buffer de 5 por assinante: chegam as 5 mais novas, depois de um aviso de 3 descartadas
        assertEquals(new LogHub.LogEvent.Dropped(3), lento.getItems().get(0));
        assertEquals(List.of("l3", "l4", "l5", "l6", "l7"), texts(lento));
    }

    @Test
    void droppedReportedByTheEdgeReachesEverySubscriber() {
        var s = hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        edgeSends(opens().get(0).streamId(), 1, 7, "depois do buraco");
        assertEquals(new LogHub.LogEvent.Dropped(7), s.getItems().get(0));
    }

    @Test
    void hubRedactsAgainWhateverTheEdgeSent() {
        var s = hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        edgeSends(opens().get(0).streamId(), 1, 0, "Authorization: Bearer abcdefghijklmnop");
        assertEquals(List.of("Authorization: [redacted:bearer]"), texts(s));
    }

    @Test
    void invalidRegexIsRefusedAndRe2jStaysLinear() {
        assertThrows(IllegalArgumentException.class, () -> hub.open("edge-vps", "docker:vps/x", null, 10, "(", true, "a"));
        var s = hub.open("edge-vps", "docker:vps/x", null, 10, "(a+)+$", true, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        long t0 = System.nanoTime();
        // abaixo do corte de 16 KiB: o "!" do fim continua la e (a+)+$ nao casa
        edgeSends(opens().get(0).streamId(), 1, 0, "a".repeat(10_000) + "!");
        assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "regex patologica nao trava o hub");
        assertEquals(0, texts(s).size());
    }

    @Test
    void plainFilterIsLiteralNotRegex() {
        var s = hub.open("edge-vps", "docker:vps/x", null, 10, "a.b", false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        edgeSends(opens().get(0).streamId(), 1, 0, "axb", "a.b ok");
        assertEquals(List.of("a.b ok"), texts(s));
    }

    @Test
    void limitsPerClientAndGlobal() {
        hub.open("edge-vps", "docker:vps/a", null, 10, null, false, "c1").subscribe().withSubscriber(AssertSubscriber.create(1));
        hub.open("edge-vps", "docker:vps/b", null, 10, null, false, "c1").subscribe().withSubscriber(AssertSubscriber.create(1));
        LogHub.LimitException perClient = assertThrows(LogHub.LimitException.class,
                () -> hub.open("edge-vps", "docker:vps/c", null, 10, null, false, "c1"));
        assertEquals("limite de 2 streams de log por usuario", perClient.getMessage());
        hub.open("edge-vps", "docker:vps/c", null, 10, null, false, "c2").subscribe().withSubscriber(AssertSubscriber.create(1));
        assertEquals("limite de 3 streams de log no hub",
                assertThrows(LogHub.LimitException.class, () -> hub.open("edge-vps", "docker:vps/d", null, 10, null, false, "c3")).getMessage());
    }

    @Test
    void lastSubscriberLeavingClosesTheFollowAfterTheIdleDelay() throws Exception {
        var s = hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        String id = opens().get(0).streamId();
        s.cancel();
        assertTrue(toEdge.stream().noneMatch(f -> f instanceof Frame.LogClose), "nao fecha na hora");
        Thread.sleep(500);
        assertEquals(new Frame.LogClose(id, "sem assinantes"), toEdge.get(toEdge.size() - 1));
        // a vaga do limite volta
        hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(1));
    }

    @Test
    void edgeEndOrDisconnectEndsTheSubscribersWithTheReason() {
        var s = hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        hub.fromEdge("edge-vps", new Frame.LogClose(opens().get(0).streamId(), "fim do log"));
        assertEquals(new LogHub.LogEvent.End("fim do log"), s.getItems().get(s.getItems().size() - 1));
        s.assertCompleted();
        var t = hub.open("edge-vps", "docker:vps/y", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        hub.edgeGone("edge-vps");
        assertEquals(new LogHub.LogEvent.End("edge desconectado"), t.getItems().get(t.getItems().size() - 1));
        t.assertCompleted();
    }

    @Test
    void streamEndsAfterTheMaximumDuration() throws Exception {
        LogHub shortHub = new LogHub((e, f) -> true, Redactor.DEFAULT, new LogHub.Limits(4, 20, 100, Duration.ofSeconds(10),
                Duration.ofMillis(200), 4));
        try {
            var s = shortHub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
            s.awaitCompletion(Duration.ofSeconds(5));
            assertEquals(new LogHub.LogEvent.End("max-duration"), s.getItems().get(s.getItems().size() - 1));
        } finally {
            shortHub.close();
        }
    }

    @Test
    void framesFromAnotherEdgeForThisStreamAreIgnored() {
        var s = hub.open("edge-vps", "docker:vps/x", null, 10, null, false, "a").subscribe().withSubscriber(AssertSubscriber.create(10));
        hub.fromEdge("edge-host", new Frame.LogLines(opens().get(0).streamId(), 1, List.of(line("mentira")), 0));
        assertEquals(List.of(), texts(s));
    }
}
