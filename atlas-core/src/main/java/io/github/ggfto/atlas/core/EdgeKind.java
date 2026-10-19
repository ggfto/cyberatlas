package io.github.ggfto.atlas.core;

/** Tipos de aresta: caminho da requisicao, porta de auth, onde roda, quem gerencia, tunel, WireGuard. */
public enum EdgeKind {
    ROUTES_TO, AUTH_VIA, RUNS_ON, MANAGES, TUNNEL, WIREGUARD, DEPENDS_ON
}
