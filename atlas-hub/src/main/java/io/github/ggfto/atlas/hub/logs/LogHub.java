package io.github.ggfto.atlas.hub.logs;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.github.ggfto.atlas.core.LogLine;
import io.github.ggfto.atlas.core.Redactor;
import io.github.ggfto.atlas.protocol.Frame;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.MultiEmitter;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fan-out de log no hub (design §2.4). Um follow por alvo no edge, compartilhado; cada assinante tem buffer
 * proprio (o lento perde as mais antigas e recebe {@code dropped}, sem frear os outros nem o edge) e filtro
 * proprio (RE2J, tempo linear). O credito volta ao edge a cada lote despachado; o hub redige de novo; limites
 * por cliente, globais e de duracao; follow sem assinante fecha depois de {@code idleClose}.
 */
public final class LogHub implements AutoCloseable {

    /** Manda um frame para o edge (o WebSocket dele); false se o edge nao esta conectado. */
    public interface EdgeSender {
        boolean send(String edgeId, Frame frame);
    }

    public record Limits(int perClient, int global, int subscriberBuffer, Duration idleClose, Duration maxDuration,
                         int initialCredit) {
    }

    public sealed interface LogEvent {
        record Line(LogLine line) implements LogEvent {
        }

        record Dropped(long n) implements LogEvent {
        }

        record End(String reason) implements LogEvent {
        }
    }

    public static final class LimitException extends RuntimeException {
        public LimitException(String message) {
            super(message);
        }
    }

    private final class Upstream {
        final String id = UUID.randomUUID().toString();
        final String edgeId;
        final String key;
        final Set<Sub> subs = new LinkedHashSet<>();
        ScheduledFuture<?> idle;

        Upstream(String edgeId, String key) {
            this.edgeId = edgeId;
            this.key = key;
        }
    }

    private final class Sub {
        final String client;
        final Pattern filter;
        final ArrayDeque<LogEvent.Line> buf = new ArrayDeque<>();
        final Upstream up;
        long dropped;
        MultiEmitter<? super LogEvent> em;
        ScheduledFuture<?> deadline;
        boolean done;

        Sub(String client, Pattern filter, Upstream up) {
            this.client = client;
            this.filter = filter;
            this.up = up;
        }
    }

    private final EdgeSender sender;
    private final Redactor redactor;
    private final Limits limits;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final Map<String, Upstream> byKey = new HashMap<>();
    private final Map<String, Upstream> byStream = new HashMap<>();
    private final Map<String, Integer> perClient = new HashMap<>();
    private int total;

    public LogHub(EdgeSender sender, Redactor redactor, Limits limits) {
        this.sender = sender;
        this.redactor = redactor;
        this.limits = limits;
    }

    public synchronized Multi<LogEvent> open(String edgeId, String nodeKey, String container, int tail, String filter,
                                             boolean regex, String client) {
        Pattern p = compile(filter, regex);
        if (perClient.getOrDefault(client, 0) >= limits.perClient()) {
            throw new LimitException("limite de " + limits.perClient() + " streams de log por usuario");
        }
        if (total >= limits.global()) {
            throw new LimitException("limite de " + limits.global() + " streams de log no hub");
        }
        String key = edgeId + "|" + nodeKey + "|" + (container == null ? "" : container);
        Upstream up = byKey.get(key);
        if (up == null) {
            up = new Upstream(edgeId, key);
            byKey.put(key, up);
            byStream.put(up.id, up);
            sender.send(edgeId, new Frame.LogOpen(up.id, nodeKey, container, tail, null, true));
            sender.send(edgeId, new Frame.Credit(up.id, limits.initialCredit()));
        } else if (up.idle != null) {
            up.idle.cancel(false);
            up.idle = null;
        }
        Sub sub = new Sub(client, p, up);
        up.subs.add(sub);
        perClient.merge(client, 1, Integer::sum);
        total++;
        return Multi.createFrom().emitter(em -> attach(sub, em));
    }

    /** Frame de log vindo de um edge (so vale para os streams que ele mesmo serve). */
    public synchronized void fromEdge(String edgeId, Frame frame) {
        switch (frame) {
            case Frame.LogLines l -> {
                Upstream up = byStream.get(l.streamId());
                if (up == null || !up.edgeId.equals(edgeId)) {
                    return;
                }
                for (Sub s : List.copyOf(up.subs)) {
                    s.dropped += l.dropped();
                    for (LogLine raw : l.lines()) {
                        LogLine clean = LogLine.of(raw.ts(), raw.stream(), raw.text(), redactor);
                        LogLine line = new LogLine(clean.ts(), clean.stream(), clean.text(), clean.truncated() || raw.truncated());
                        if (s.filter == null || s.filter.matcher(line.text()).find()) {
                            offer(s, line);
                            // entrega na hora a quem tem demanda: so o assinante lento acumula (e descarta)
                            drain(s);
                        }
                    }
                    drain(s);
                }
                sender.send(edgeId, new Frame.Credit(l.streamId(), 1));
            }
            case Frame.LogClose c -> {
                Upstream up = byStream.get(c.streamId());
                if (up != null && up.edgeId.equals(edgeId)) {
                    endAll(up, c.reason());
                }
            }
            default -> {
            }
        }
    }

    /** O edge caiu: os streams dele terminam (o browser reabre quando ele voltar). */
    public synchronized void edgeGone(String edgeId) {
        for (Upstream up : List.copyOf(byKey.values())) {
            if (up.edgeId.equals(edgeId)) {
                endAll(up, "edge desconectado");
            }
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }

    private synchronized void attach(Sub sub, MultiEmitter<? super LogEvent> em) {
        sub.em = em;
        em.onRequest(n -> drainLocked(sub));
        em.onTermination(() -> leave(sub));
        sub.deadline = timer.schedule(() -> end(sub, "max-duration"), limits.maxDuration().toMillis(), TimeUnit.MILLISECONDS);
        if (sub.done) {
            em.complete();
            return;
        }
        drain(sub);
    }

    private synchronized void drainLocked(Sub sub) {
        drain(sub);
    }

    private void offer(Sub s, LogLine line) {
        if (s.buf.size() >= limits.subscriberBuffer()) {
            s.buf.poll();
            s.dropped++;
        }
        s.buf.add(new LogEvent.Line(line));
    }

    private void drain(Sub s) {
        if (s.em == null || s.done) {
            return;
        }
        while (s.em.requested() > 0) {
            if (s.dropped > 0) {
                long n = s.dropped;
                s.dropped = 0;
                s.em.emit(new LogEvent.Dropped(n));
                continue;
            }
            LogEvent.Line l = s.buf.poll();
            if (l == null) {
                return;
            }
            s.em.emit(l);
        }
    }

    private synchronized void end(Sub s, String reason) {
        if (s.done) {
            return;
        }
        drain(s);
        s.done = true;
        if (s.em != null) {
            s.em.emit(new LogEvent.End(reason));
            s.em.complete();
        }
        leave(s);
    }

    private void endAll(Upstream up, String reason) {
        byKey.remove(up.key);
        byStream.remove(up.id);
        for (Sub s : new ArrayList<>(up.subs)) {
            end(s, reason);
        }
    }

    private synchronized void leave(Sub s) {
        if (!s.up.subs.remove(s)) {
            return;
        }
        s.done = true;
        if (s.deadline != null) {
            s.deadline.cancel(false);
        }
        perClient.computeIfPresent(s.client, (k, v) -> v <= 1 ? null : v - 1);
        total--;
        Upstream up = s.up;
        if (up.subs.isEmpty() && byStream.containsKey(up.id)) {
            up.idle = timer.schedule(() -> closeIfEmpty(up), limits.idleClose().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void closeIfEmpty(Upstream up) {
        if (up.subs.isEmpty() && byStream.containsKey(up.id)) {
            byKey.remove(up.key);
            byStream.remove(up.id);
            sender.send(up.edgeId, new Frame.LogClose(up.id, "sem assinantes"));
        }
    }

    private static Pattern compile(String filter, boolean regex) {
        if (filter == null || filter.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(regex ? filter : Pattern.quote(filter));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("regex invalida: " + e.getDescription());
        }
    }
}
