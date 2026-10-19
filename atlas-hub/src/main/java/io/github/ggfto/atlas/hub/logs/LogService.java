package io.github.ggfto.atlas.hub.logs;

import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Redactor;
import io.github.ggfto.atlas.core.TopologyNode;
import io.github.ggfto.atlas.hub.AtlasConfig;
import io.github.ggfto.atlas.hub.TopologyService;
import io.github.ggfto.atlas.hub.auth.Roles;
import io.github.ggfto.atlas.hub.store.AuditLog;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.github.ggfto.atlas.hub.edge.EdgeHub;
import io.github.ggfto.atlas.protocol.Frame;
import io.github.ggfto.atlas.protocol.FrameCodec;
import io.quarkus.websockets.next.OpenConnections;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Log ao vivo no hub: valida o alvo (container ou pod do mapa, com o edge da maquina conectado) e liga o
 * {@link LogHub} ao WebSocket do edge. Envio nao bloqueante: o LogHub segura lock e nunca espera a rede.
 */
@ApplicationScoped
public class LogService {

    private static final Logger LOG = Logger.getLogger(LogService.class);
    private static final Set<NodeKind> WITH_LOGS = Set.of(NodeKind.CONTAINER, NodeKind.POD);

    /** Alvo que nao tem log (nao existe, nao e container/pod): 400. */
    public static final class BadTarget extends RuntimeException {
        BadTarget(String message) {
            super(message);
        }
    }

    /** Alvo existe mas o edge da maquina dele nao esta conectado: 503. */
    public static final class EdgeOffline extends RuntimeException {
        EdgeOffline(String message) {
            super(message);
        }
    }

    private final boolean enabled;
    private final EdgeHub edges;
    private final TopologyService topology;
    private final OpenConnections connections;
    private final LogHub hub;
    private final AuditLog audit;

    LogService(AtlasConfig config, EdgeHub edges, TopologyService topology, OpenConnections connections, AuditLog audit) {
        this.audit = audit;
        AtlasConfig.Logs c = config.logs();
        this.enabled = c.enabled();
        this.edges = edges;
        this.topology = topology;
        this.connections = connections;
        this.hub = new LogHub(this::send, Redactor.DEFAULT, new LogHub.Limits(c.perUser(), c.global(), c.subscriberBuffer(),
                c.idleClose(), c.maxDuration(), c.initialCredit()));
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Abre (ou entra em) um follow. O papel e conferido de novo aqui, antes de qualquer LogOpen ir ao edge (design
     * §4): a rota ja exige atlas-admins, mas um caminho novo que chame isto sem a rota nao vaza log.
     */
    public Multi<LogHub.LogEvent> open(String nodeKey, String container, int tail, String filter, boolean regex, SecurityIdentity who) {
        if (who == null || who.isAnonymous() || !who.hasRole(Roles.ADMIN)) {
            throw new ForbiddenException("log so para " + Roles.ADMIN);
        }
        String user = who.getPrincipal().getName();
        TopologyNode target = topology.snapshot().nodes().stream()
                .filter(n -> n.node().key().equals(nodeKey) && WITH_LOGS.contains(n.node().kind()))
                .findFirst().orElseThrow(() -> new BadTarget("alvo sem log: " + nodeKey));
        String edgeId = "edge-" + target.node().machine();
        if (edges.registry().session(edgeId).isEmpty()) {
            throw new EdgeOffline("edge da maquina " + target.node().machine() + " desconectado");
        }
        Multi<LogHub.LogEvent> events = hub.open(edgeId, nodeKey, container, tail, filter, regex, user);
        audit.record(user, "log-aberto", nodeKey);
        return events;
    }

    /** Containers e pods cujo edge esta conectado agora. */
    public List<String> targets() {
        return topology.snapshot().nodes().stream()
                .filter(n -> WITH_LOGS.contains(n.node().kind()))
                .filter(n -> edges.registry().session("edge-" + n.node().machine()).isPresent())
                .map(n -> n.node().key()).toList();
    }

    public void fromEdge(String edgeId, Frame frame) {
        hub.fromEdge(edgeId, frame);
    }

    public void edgeGone(String edgeId) {
        hub.edgeGone(edgeId);
    }

    @PreDestroy
    void stop() {
        hub.close();
    }

    private boolean send(String edgeId, Frame frame) {
        Optional<String> session = edges.registry().session(edgeId);
        var conn = session.flatMap(connections::findByConnectionId);
        if (conn.isEmpty()) {
            return false;
        }
        conn.get().sendText(FrameCodec.encode(frame)).subscribe().with(v -> {
        }, err -> LOG.debugf("envio de %s para %s falhou: %s", frame.getClass().getSimpleName(), edgeId, err.getMessage()));
        return true;
    }
}
