package io.github.ggfto.atlas.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.ggfto.atlas.core.Fragment;
import java.util.List;
import java.util.Map;

/**
 * Frames edge ⇄ hub (design §1.1), um por mensagem de texto do WebSocket. A identidade do edge vem do CN do
 * certificado mTLS, nunca do {@code Hello}. Frames de log (LogOpen/LogLines/Credit) entram na F4, patch na F3.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Frame.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = Frame.Welcome.class, name = "welcome"),
    @JsonSubTypes.Type(value = Frame.Reject.class, name = "reject"),
    @JsonSubTypes.Type(value = Frame.Snapshot.class, name = "snapshot"),
    @JsonSubTypes.Type(value = Frame.Resync.class, name = "resync"),
    @JsonSubTypes.Type(value = Frame.Heartbeat.class, name = "heartbeat"),
})
public sealed interface Frame {

    /** edge → hub: primeiro frame da sessao. */
    record Hello(String edgeId, int protocolVersion, String machine, List<String> capabilities,
                 Map<String, String> adapterVersions) implements Frame {
    }

    /** hub → edge: sessao aceita, com a config de coleta. */
    record Welcome(String sessionId, int acceptedVersion, long collectEveryMs, long ttlMs) implements Frame {
    }

    /** hub → edge: recusa definitiva (versao, edge revogado); o edge nao reconecta em loop rapido. */
    record Reject(String reason) implements Frame {
    }

    /** edge → hub: estado completo de uma fonte local. */
    record Snapshot(String source, long seq, Fragment fragment) implements Frame {
    }

    /** hub → edge: pede um snapshot novo da fonte. */
    record Resync(String source) implements Frame {
    }

    /** os dois sentidos, a cada ~20 s. */
    record Heartbeat(long epochMs) implements Frame {
    }
}
