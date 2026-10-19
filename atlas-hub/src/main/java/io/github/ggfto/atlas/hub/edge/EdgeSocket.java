package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.core.Expectation;
import io.github.ggfto.atlas.hub.AtlasConfig;
import io.github.ggfto.atlas.hub.TopologyService;
import io.github.ggfto.atlas.protocol.Frame;
import io.github.ggfto.atlas.protocol.FrameCodec;
import io.github.ggfto.atlas.protocol.ProtocolException;
import io.github.ggfto.atlas.protocol.ProtocolVersion;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData.TypedKey;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * WebSocket dos edges (design §1.1). Primeiro frame tem de ser {@code Hello} do mesmo edge do certificado e numa
 * versao da janela; depois so {@code Snapshot} e {@code Heartbeat}. Qualquer outra coisa encerra a sessao.
 */
@WebSocket(path = "/edge/v1", endpointId = EdgeSocket.ENDPOINT)
public class EdgeSocket {

    static final String ENDPOINT = "atlas-edge";
    static final TypedKey<String> EDGE_ID = TypedKey.forString("atlas.edgeId");
    static final TypedKey<String> MACHINE = TypedKey.forString("atlas.machine");
    private static final TypedKey<Boolean> GREETED = TypedKey.forBoolean("atlas.greeted");
    private static final Logger LOG = Logger.getLogger(EdgeSocket.class);

    private final EdgeHub hub;
    private final TopologyService topology;
    private final AtlasConfig config;

    EdgeSocket(EdgeHub hub, TopologyService topology, AtlasConfig config) {
        this.hub = hub;
        this.topology = topology;
        this.config = config;
    }

    @OnOpen
    void open(WebSocketConnection c) {
        hub.registry().open(c.userData().get(EDGE_ID), c.id());
    }

    @OnTextMessage
    void message(String text, WebSocketConnection c) {
        String edgeId = c.userData().get(EDGE_ID);
        String machine = c.userData().get(MACHINE);
        Frame frame;
        try {
            frame = FrameCodec.decode(text);
        } catch (ProtocolException e) {
            reject(c, e.getMessage());
            return;
        }
        boolean greeted = Boolean.TRUE.equals(c.userData().get(GREETED));
        if (!greeted) {
            if (!(frame instanceof Frame.Hello h)) {
                reject(c, "o primeiro frame tem de ser hello");
                return;
            }
            if (!edgeId.equals(h.edgeId())) {
                reject(c, "hello de " + h.edgeId() + " num certificado de " + edgeId);
                return;
            }
            Optional<String> bad = ProtocolVersion.rejection(h.protocolVersion(), ProtocolVersion.CURRENT);
            if (bad.isPresent()) {
                reject(c, bad.get());
                return;
            }
            c.userData().put(GREETED, true);
            c.sendTextAndAwait(FrameCodec.encode(new Frame.Welcome(c.id(), ProtocolVersion.CURRENT,
                    config.collect().every().toMillis(), config.checks().ttl().toMillis(), checks(machine))));
            LOG.infof("edge %s (%s) conectado", edgeId, machine);
            return;
        }
        switch (frame) {
            case Frame.Snapshot s -> topology.accept(EdgeFragmentGuard.accept(s.fragment(), machine, topology::machineOf,
                    Instant.now(), config.checks().ttl()));
            case Frame.Heartbeat h -> c.sendTextAndAwait(FrameCodec.encode(new Frame.Heartbeat(System.currentTimeMillis())));
            default -> reject(c, "frame inesperado do edge: " + frame.getClass().getSimpleName());
        }
    }

    @OnClose
    void close(WebSocketConnection c) {
        String edgeId = c.userData().get(EDGE_ID);
        if (edgeId != null && hub.registry().close(edgeId, c.id())) {
            LOG.infof("edge %s desconectado", edgeId);
        }
    }

    private List<Frame.LocalCheck> checks(String machine) {
        return topology.localTargets(machine).stream().map(t -> {
            Expectation e = t.expectation();
            String authHost = switch (e) {
                case Expectation.Protected p -> p.authHost();
                case Expectation.Open o -> o.authHost();
            };
            return new Frame.LocalCheck(t.nodeKey(), t.url().toString(), authHost, e instanceof Expectation.Protected);
        }).toList();
    }

    private static void reject(WebSocketConnection c, String reason) {
        LOG.warnf("edge %s recusado: %s", c.userData().get(EDGE_ID), reason);
        try {
            c.sendTextAndAwait(FrameCodec.encode(new Frame.Reject(reason)));
            c.closeAndAwait();
        } catch (RuntimeException e) {
            // o edge fecha o socket ao ler o Reject; o close do lado de ca pode chegar num canal ja fechado
            LOG.debugf("edge %s ja tinha fechado: %s", c.userData().get(EDGE_ID), e.getClass().getSimpleName());
        }
    }
}
