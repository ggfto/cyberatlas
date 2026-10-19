package io.github.ggfto.atlas.core;

/** Pedido de follow: alvo (chave do no), container (pod com mais de um), linhas iniciais e se segue. */
public record LogRequest(String nodeKey, String container, int tail, boolean follow) {

    public static final int DEFAULT_TAIL = 200;
    public static final int MAX_TAIL = 2000;

    public LogRequest {
        tail = tail <= 0 ? DEFAULT_TAIL : Math.min(tail, MAX_TAIL);
    }
}
