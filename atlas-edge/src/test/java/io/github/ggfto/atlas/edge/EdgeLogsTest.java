package io.github.ggfto.atlas.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.LogLine;
import io.github.ggfto.atlas.core.LogRequest;
import io.github.ggfto.atlas.core.LogSource;
import io.github.ggfto.atlas.core.Redactor;
import io.github.ggfto.atlas.protocol.Frame;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Controle de fluxo do log no edge (design §1.1): so manda lote com credito; sem credito guarda ate o teto e
 * descarta as mais antigas, contando em dropped. Toda linha sai redigida e cortada.
 */
class EdgeLogsTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");

    /** Fonte controlada pelo teste: o teste empurra linhas e encerra. */
    static final class FakeSource implements LogSource {
        Consumer<RawLine> out;
        Runnable end;
        boolean closed;
        LogRequest request;

        public String name() {
            return "docker";
        }

        public boolean handles(String key) {
            return key.startsWith("docker:vps/");
        }

        public AutoCloseable follow(LogRequest req, Consumer<RawLine> out, Runnable onEnd) {
            this.request = req;
            this.out = out;
            this.end = onEnd;
            return () -> closed = true;
        }

        void line(String text) {
            out.accept(new RawLine(AT, "stdout", text));
        }
    }

    final FakeSource src = new FakeSource();
    final List<Frame> sent = new CopyOnWriteArrayList<>();
    final EdgeLogs logs = new EdgeLogs(List.of(src), f -> sent.add(f), Redactor.DEFAULT, 2, 3, 2, 1_000_000);

    List<Frame.LogLines> batches() {
        return sent.stream().filter(f -> f instanceof Frame.LogLines).map(f -> (Frame.LogLines) f).toList();
    }

    @Test
    void nothingIsSentWithoutCredit() {
        logs.open(new Frame.LogOpen("s1", "docker:vps/9router", null, 50, null, true));
        assertEquals(50, src.request.tail());
        src.line("a");
        assertEquals(List.of(), batches());
        logs.credit(new Frame.Credit("s1", 1));
        assertEquals(1, batches().size());
        assertEquals(List.of("a"), batches().get(0).lines().stream().map(LogLine::text).toList());
    }

    @Test
    void creditCountsBatchesAndBatchesHaveACeiling() {
        logs.open(new Frame.LogOpen("s1", "docker:vps/9router", null, 10, null, true));
        logs.credit(new Frame.Credit("s1", 2));
        src.line("a");
        src.line("b");
        src.line("c");
        // 2 creditos: o primeiro lote saiu com "a"; o segundo com "b"; "c" espera credito
        assertEquals(2, batches().size());
        logs.credit(new Frame.Credit("s1", 5));
        assertEquals(List.of("c"), batches().get(2).lines().stream().map(LogLine::text).toList());
        assertEquals(List.of(1L, 2L, 3L), batches().stream().map(Frame.LogLines::seq).toList());
    }

    @Test
    void withoutCreditTheOldestAreDroppedAndCounted() {
        logs.open(new Frame.LogOpen("s1", "docker:vps/9router", null, 10, null, true));
        for (String l : List.of("1", "2", "3", "4", "5")) {
            src.line(l);
        }
        logs.credit(new Frame.Credit("s1", 1));
        Frame.LogLines b = batches().get(0);
        assertEquals(List.of("3", "4"), b.lines().stream().map(LogLine::text).toList());
        assertEquals(2, b.dropped());
        logs.credit(new Frame.Credit("s1", 1));
        assertEquals(0, batches().get(1).dropped(), "dropped zera depois de informado");
    }

    @Test
    void linesLeaveRedactedAndCut() {
        logs.open(new Frame.LogOpen("s1", "docker:vps/9router", null, 10, null, true));
        logs.credit(new Frame.Credit("s1", 5));
        src.line("password=hunter2");
        src.line("x".repeat(LogLine.MAX_CHARS + 1));
        assertEquals("password=[redacted:kv]", batches().get(0).lines().get(0).text());
        assertTrue(batches().get(1).lines().get(0).truncated());
    }

    @Test
    void unknownTargetAndTooManyStreamsAreClosedWithTheReason() {
        logs.open(new Frame.LogOpen("s1", "docker:host/outra-maquina", null, 10, null, true));
        assertEquals(new Frame.LogClose("s1", "alvo sem fonte de log nesta maquina"), sent.get(0));
        logs.open(new Frame.LogOpen("s2", "docker:vps/a", null, 10, null, true));
        logs.open(new Frame.LogOpen("s3", "docker:vps/b", null, 10, null, true));
        logs.open(new Frame.LogOpen("s4", "docker:vps/c", null, 10, null, true));
        assertEquals(new Frame.LogClose("s4", "limite de streams do edge"), sent.get(sent.size() - 1));
    }

    @Test
    void closeFromTheHubStopsTheFollowAndSourceEndIsReported() {
        logs.open(new Frame.LogOpen("s1", "docker:vps/9router", null, 10, null, true));
        logs.close(new Frame.LogClose("s1", "sem assinantes"));
        assertTrue(src.closed);
        logs.open(new Frame.LogOpen("s2", "docker:vps/9router", null, 10, null, true));
        src.end.run();
        assertEquals(new Frame.LogClose("s2", "fim do log"), sent.get(sent.size() - 1));
    }

    @Test
    void linkDownClosesEverything() {
        logs.open(new Frame.LogOpen("s1", "docker:vps/9router", null, 10, null, true));
        logs.closeAll();
        assertTrue(src.closed);
    }
}
