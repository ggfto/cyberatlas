package io.github.ggfto.atlas.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.jboss.logging.Logger;

/**
 * A conexao do edge com o hub (design §1.1, molde do WebSocketFrameChannel do hive-agent): um WebSocket de saida
 * com mTLS, {@code Hello} ao abrir, heartbeat, reconexao com backoff e jitter. Um {@code Reject} nao vira loop
 * rapido: a proxima tentativa so depois de {@code rejectDelay}. Sem resposta por 3 heartbeats, derruba e reconecta.
 */
public final class EdgeLink implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EdgeLink.class);

    public interface Handler {
        void onWelcome(Frame.Welcome welcome);

        default void onReject(String reason) {
        }

        default void onResync(String source) {
        }
    }

    private final URI uri;
    private final SSLContext ssl;
    private final Supplier<Frame.Hello> hello;
    private final Backoff backoff;
    private final Duration heartbeat;
    private final Duration rejectDelay;
    private final Handler handler;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
    private final AtomicInteger attempt = new AtomicInteger();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile WebSocket ws;
    private volatile String sessionId;
    private volatile Instant lastSeen = Instant.now();
    private volatile boolean closed;

    public EdgeLink(URI uri, SSLContext ssl, Supplier<Frame.Hello> hello, Backoff backoff, Duration heartbeat,
                    Duration rejectDelay, Handler handler) {
        this.uri = uri;
        this.ssl = ssl;
        this.hello = hello;
        this.backoff = backoff;
        this.heartbeat = heartbeat;
        this.rejectDelay = rejectDelay;
        this.handler = handler;
    }

    public void start() {
        timer.scheduleAtFixedRate(this::tick, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS);
        connect();
    }

    /** Sessao aceita pelo hub (Welcome recebido) e socket aberto. */
    public boolean connected() {
        return ws != null && sessionId != null && !ws.isOutputClosed();
    }

    public synchronized boolean send(Frame frame) {
        WebSocket w = ws;
        if (w == null || (sessionId == null && !(frame instanceof Frame.Hello))) {
            return false;
        }
        try {
            w.sendText(FrameCodec.encode(frame), true).get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            LOG.debugf("envio de %s falhou: %s", frame.getClass().getSimpleName(), e.getMessage());
            drop();
            return false;
        }
    }

    private void connect() {
        if (closed) {
            return;
        }
        reconnectScheduled.set(false);
        HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(10)).build();
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, new Listener())
                .whenComplete((w, err) -> {
                    if (err != null) {
                        LOG.infof("hub inacessivel (%s)", err.getClass().getSimpleName());
                        scheduleReconnect(backoff.delay(attempt.incrementAndGet()));
                        return;
                    }
                    ws = w;
                    lastSeen = Instant.now();
                    send(hello.get());
                });
    }

    private void tick() {
        if (ws == null || sessionId == null) {
            return;
        }
        if (Duration.between(lastSeen, Instant.now()).compareTo(heartbeat.multipliedBy(3)) > 0) {
            LOG.info("hub sem resposta por 3 heartbeats; reconectando");
            drop();
            return;
        }
        send(new Frame.Heartbeat(System.currentTimeMillis()));
    }

    private void drop() {
        WebSocket w = ws;
        ws = null;
        sessionId = null;
        if (w != null) {
            w.abort();
        }
        scheduleReconnect(backoff.delay(attempt.incrementAndGet()));
    }

    private void scheduleReconnect(Duration delay) {
        if (closed || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        timer.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        WebSocket w = ws;
        ws = null;
        sessionId = null;
        if (w != null) {
            w.abort();
        }
        timer.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                handle(webSocket, text);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(WebSocket webSocket, String text) {
            lastSeen = Instant.now();
            Frame f;
            try {
                f = FrameCodec.decode(text);
            } catch (ProtocolException e) {
                LOG.warnf("frame invalido do hub: %s", e.getMessage());
                drop();
                return;
            }
            switch (f) {
                case Frame.Welcome w -> {
                    sessionId = w.sessionId();
                    attempt.set(0);
                    handler.onWelcome(w);
                }
                case Frame.Reject r -> {
                    LOG.warnf("hub recusou o edge: %s", r.reason());
                    handler.onReject(r.reason());
                    WebSocket cur = ws;
                    ws = null;
                    sessionId = null;
                    if (cur != null) {
                        cur.abort();
                    }
                    scheduleReconnect(rejectDelay);
                }
                case Frame.Resync r -> handler.onResync(r.source());
                case Frame.Heartbeat h -> {
                }
                default -> {
                    LOG.warnf("frame inesperado do hub: %s", f.getClass().getSimpleName());
                    drop();
                }
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (webSocket == ws) {
                ws = null;
                sessionId = null;
                scheduleReconnect(backoff.delay(attempt.incrementAndGet()));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (webSocket == ws) {
                ws = null;
                sessionId = null;
                scheduleReconnect(backoff.delay(attempt.incrementAndGet()));
            }
        }
    }
}
