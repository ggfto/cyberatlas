package io.github.ggfto.atlas.core;

import java.util.function.Consumer;

/**
 * Fonte de log local do edge (design §2.2): sabe seguir certos nos ({@code docker:m/...}, {@code k8s:m/ns/pod/...}).
 * O follow entrega linhas cruas ao consumidor ate ser fechado; quem redige e corta e quem consome.
 */
public interface LogSource {

    String name();

    boolean handles(String nodeKey);

    /** Comeca a seguir; o {@link AutoCloseable} devolvido para o follow e libera a conexao. */
    AutoCloseable follow(LogRequest request, Consumer<RawLine> out, Runnable onEnd) throws Exception;

    /** Linha como veio da fonte, antes de redacao e corte. */
    record RawLine(java.time.Instant ts, String stream, String text) {
    }
}
