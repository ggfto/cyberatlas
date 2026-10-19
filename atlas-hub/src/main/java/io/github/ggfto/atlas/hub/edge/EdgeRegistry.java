package io.github.ggfto.atlas.hub.edge;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qual sessao e dona de cada edge. A reconexao instala a sessao nova antes de o close da sessao meio aberta
 * chegar; o close so remove a entrada se vier da sessao que e dona dela (licao documentada no hive-agent).
 */
public final class EdgeRegistry {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    public void open(String edgeId, String sessionId) {
        sessions.put(edgeId, sessionId);
    }

    /** {@code true} se a sessao era a dona e o edge saiu; {@code false} para close atrasado (nao-evento). */
    public boolean close(String edgeId, String sessionId) {
        return sessions.remove(edgeId, sessionId);
    }

    public Optional<String> session(String edgeId) {
        return Optional.ofNullable(sessions.get(edgeId));
    }
}
