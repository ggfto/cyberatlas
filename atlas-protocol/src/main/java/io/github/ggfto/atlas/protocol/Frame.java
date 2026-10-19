package io.github.ggfto.atlas.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.github.ggfto.atlas.core.Fragment;
import java.util.List;
import java.util.Map;

/**
 * Frames edge ⇄ hub (design §1.1), um por mensagem de texto do WebSocket. A identidade do edge vem do CN do
 * certificado mTLS, nunca do {@code Hello}. Log (F4): o hub abre com {@code LogOpen} e da {@code Credit}; o edge so
 * manda {@code LogLines} com credito e, sem credito, descarta as mais antigas e conta em {@code dropped}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Frame.Hello.class, name = "hello"),
    @JsonSubTypes.Type(value = Frame.Welcome.class, name = "welcome"),
    @JsonSubTypes.Type(value = Frame.Reject.class, name = "reject"),
    @JsonSubTypes.Type(value = Frame.Snapshot.class, name = "snapshot"),
    @JsonSubTypes.Type(value = Frame.Resync.class, name = "resync"),
    @JsonSubTypes.Type(value = Frame.Heartbeat.class, name = "heartbeat"),
    @JsonSubTypes.Type(value = Frame.LogOpen.class, name = "log-open"),
    @JsonSubTypes.Type(value = Frame.LogLines.class, name = "log-lines"),
    @JsonSubTypes.Type(value = Frame.Credit.class, name = "credit"),
    @JsonSubTypes.Type(value = Frame.LogClose.class, name = "log-close"),
})
public sealed interface Frame {

    /** edge → hub: primeiro frame da sessao. */
    record Hello(String edgeId, int protocolVersion, String machine, List<String> capabilities,
                 Map<String, String> adapterVersions) implements Frame {
    }

    /**
     * hub → edge: sessao aceita, com a config de coleta e os checks locais desta maquina (backends e rotas
     * {@code *.local} do services.yml), que so o edge alcanca.
     */
    record Welcome(String sessionId, int acceptedVersion, long collectEveryMs, long ttlMs, List<LocalCheck> checks)
            implements Frame {
        public Welcome {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /** Um GET que o edge faz de dentro da maquina; {@code authHost} nao nulo = rota protegida. */
    record LocalCheck(String nodeKey, String url, String authHost, boolean protectedRoute) {
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

    /** hub → edge: abre um follow local. {@code container} so para pod com mais de um. */
    record LogOpen(String streamId, String nodeKey, String container, int tail, Long sinceEpochMs, boolean follow) implements Frame {
    }

    /** edge → hub: um lote de linhas ja redigidas; {@code dropped} = descartadas por falta de credito desde o lote anterior. */
    record LogLines(String streamId, long seq, List<io.github.ggfto.atlas.core.LogLine> lines, long dropped) implements Frame {
        public LogLines {
            lines = List.copyOf(lines);
        }
    }

    /** hub → edge: pode mandar mais {@code batches} lotes neste stream. */
    record Credit(String streamId, int batches) implements Frame {
    }

    /** os dois sentidos: fim do stream, com o motivo. */
    record LogClose(String streamId, String reason) implements Frame {
    }
}
