package io.github.ggfto.atlas.edge;

import io.github.ggfto.atlas.core.LogLine;
import io.github.ggfto.atlas.core.LogRequest;
import io.github.ggfto.atlas.core.LogSource;
import io.github.ggfto.atlas.core.Redactor;
import io.github.ggfto.atlas.protocol.Frame;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jboss.logging.Logger;

/**
 * Follows de log abertos pelo hub (design §1.1, controle de fluxo): toda linha sai redigida e cortada; lote so
 * com credito; sem credito guarda ate {@code bufferLines} e descarta as mais antigas, contando em {@code dropped}
 * (o edge nunca bloqueia a fonte nem acumula sem limite).
 */
public final class EdgeLogs {

    private static final Logger LOG = Logger.getLogger(EdgeLogs.class);

    private final class Stream {
        final String id;
        final ArrayDeque<LogLine> queue = new ArrayDeque<>();
        AutoCloseable handle;
        int credits;
        long dropped;
        long seq;

        Stream(String id) {
            this.id = id;
        }
    }

    private final List<LogSource> sources;
    private final Predicate<Frame> sender;
    private final Redactor redactor;
    private final int maxStreams;
    private final int bufferLines;
    private final int batchMaxLines;
    private final int batchMaxChars;
    private final Map<String, Stream> streams = new HashMap<>();

    public EdgeLogs(List<LogSource> sources, Predicate<Frame> sender, Redactor redactor, int maxStreams, int bufferLines,
                    int batchMaxLines, int batchMaxChars) {
        this.sources = List.copyOf(sources);
        this.sender = sender;
        this.redactor = redactor;
        this.maxStreams = maxStreams;
        this.bufferLines = bufferLines;
        this.batchMaxLines = batchMaxLines;
        this.batchMaxChars = batchMaxChars;
    }

    public synchronized void open(Frame.LogOpen o) {
        LogSource src = sources.stream().filter(s -> s.handles(o.nodeKey())).findFirst().orElse(null);
        if (src == null) {
            sender.test(new Frame.LogClose(o.streamId(), "alvo sem fonte de log nesta maquina"));
            return;
        }
        if (streams.size() >= maxStreams) {
            sender.test(new Frame.LogClose(o.streamId(), "limite de streams do edge"));
            return;
        }
        Stream st = new Stream(o.streamId());
        streams.put(o.streamId(), st);
        try {
            st.handle = src.follow(new LogRequest(o.nodeKey(), o.container(), o.tail(), o.follow()),
                    raw -> line(st, LogLine.of(raw.ts(), raw.stream(), raw.text(), redactor)), () -> ended(st));
        } catch (Exception e) {
            streams.remove(o.streamId());
            LOG.warnf("log de %s nao abriu: %s", o.nodeKey(), e.getMessage());
            sender.test(new Frame.LogClose(o.streamId(), "log nao abriu: " + e.getMessage()));
        }
    }

    public synchronized void credit(Frame.Credit c) {
        Stream st = streams.get(c.streamId());
        if (st != null) {
            st.credits += Math.max(0, c.batches());
            flush(st);
        }
    }

    public synchronized void close(Frame.LogClose c) {
        Stream st = streams.remove(c.streamId());
        if (st != null) {
            stop(st);
        }
    }

    /** Link com o hub caiu: os follows param (o hub reabre quando o browser reconectar). */
    public synchronized void closeAll() {
        streams.values().forEach(this::stop);
        streams.clear();
    }

    private synchronized void line(Stream st, LogLine line) {
        if (streams.get(st.id) != st) {
            return;
        }
        if (st.queue.size() >= bufferLines) {
            st.queue.poll();
            st.dropped++;
        }
        st.queue.add(line);
        flush(st);
    }

    private synchronized void ended(Stream st) {
        if (streams.get(st.id) != st) {
            return;
        }
        flush(st);
        streams.remove(st.id);
        sender.test(new Frame.LogClose(st.id, "fim do log"));
    }

    private void flush(Stream st) {
        while (st.credits > 0 && !st.queue.isEmpty()) {
            List<LogLine> batch = new ArrayList<>();
            int chars = 0;
            while (!st.queue.isEmpty() && batch.size() < batchMaxLines
                    && (batch.isEmpty() || chars + st.queue.peek().text().length() <= batchMaxChars)) {
                LogLine l = st.queue.poll();
                chars += l.text().length();
                batch.add(l);
            }
            if (sender.test(new Frame.LogLines(st.id, ++st.seq, batch, st.dropped))) {
                st.dropped = 0;
                st.credits--;
            } else {
                return;
            }
        }
    }

    private void stop(Stream st) {
        try {
            if (st.handle != null) {
                st.handle.close();
            }
        } catch (Exception e) {
            LOG.debugf("fechar follow %s: %s", st.id, e.getMessage());
        }
    }
}
