package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.protocol.Backoff;
import io.github.ggfto.atlas.protocol.EdgeLink;
import io.github.ggfto.atlas.protocol.Frame;
import io.github.ggfto.atlas.protocol.FrameCodec;
import io.github.ggfto.atlas.protocol.ProtocolVersion;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Hub falso (Vert.x, sem TLS) que aceita o upgrade e decide o que fazer com o Hello de cada sessao: o EdgeLink tem
 * de abandonar uma sessao muda no prazo do Hello e nunca derrubar uma sessao que ja recebeu Welcome.
 */
class EdgeLinkHelloTimeoutTest {

    static final Duration HELLO_TIMEOUT = Duration.ofSeconds(2);

    private final Vertx vertx = Vertx.vertx();
    private final AtomicInteger sessions = new AtomicInteger();
    private final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
    private EdgeLink link;

    @AfterEach
    void close() throws Exception {
        if (link != null) {
            link.close();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void swallowedHelloIsAbandonedAndTheNextSessionGetsTheReject() throws Exception {
        start((session, ws) -> {
            if (session > 1) {
                ws.writeTextMessage(FrameCodec.encode(new Frame.Reject("hello de edge-vps num certificado de edge-lab")));
            }
        });
        long t0 = System.nanoTime();
        assertEquals(new Frame.Reject("hello de edge-vps num certificado de edge-lab"), frames.poll(10, TimeUnit.SECONDS));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertEquals(2, sessions.get());
        assertTrue(ms >= HELLO_TIMEOUT.toMillis(), "abandonou a sessao antes do prazo: " + ms + " ms");
    }

    @Test
    void welcomedSessionIsNeverDroppedByTheHelloDeadline() throws Exception {
        start((session, ws) -> ws.writeTextMessage(FrameCodec.encode(
                new Frame.Welcome("s" + session, ProtocolVersion.CURRENT, 60_000, 90_000, List.of()))));
        assertTrue(frames.poll(10, TimeUnit.SECONDS) instanceof Frame.Welcome);
        TimeUnit.MILLISECONDS.sleep(HELLO_TIMEOUT.toMillis() * 2);
        assertEquals(1, sessions.get(), "sessao com Welcome foi derrubada");
        assertTrue(link.connected());
    }

    /** Sobe o hub falso; {@code onHello} recebe o numero da sessao (1, 2, ...) e o socket dela. */
    private void start(BiConsumer<Integer, io.vertx.core.http.ServerWebSocket> onHello) throws Exception {
        HttpServer server = vertx.createHttpServer().webSocketHandler(ws -> {
            int session = sessions.incrementAndGet();
            ws.textMessageHandler(text -> onHello.accept(session, ws));
        }).listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        link = new EdgeLink(URI.create("ws://127.0.0.1:" + server.actualPort() + "/edge/v1"), SSLContext.getDefault(),
                () -> new Frame.Hello("edge-vps", ProtocolVersion.CURRENT, "vps", List.of(), Map.of()),
                new Backoff(Duration.ofMillis(200), Duration.ofSeconds(1), () -> 0.0), Duration.ofSeconds(5),
                Duration.ofHours(1), HELLO_TIMEOUT, new EdgeLink.Handler() {
                    public void onWelcome(Frame.Welcome w) {
                        frames.add(w);
                    }

                    public void onReject(String reason) {
                        frames.add(new Frame.Reject(reason));
                    }
                });
        link.start();
    }
}
