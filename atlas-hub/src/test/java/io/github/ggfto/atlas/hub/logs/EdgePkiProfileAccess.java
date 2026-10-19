package io.github.ggfto.atlas.hub.logs;

import java.nio.file.Path;

/** Acesso ao diretorio da PKI gerada pelo EdgePkiProfile (pacote edge). */
final class EdgePkiProfileAccess {

    private EdgePkiProfileAccess() {
    }

    static Path ca() throws Exception {
        var f = io.github.ggfto.atlas.hub.edge.EdgePkiProfile.class.getDeclaredField("dir");
        f.setAccessible(true);
        return ((Path) f.get(null)).resolve("ca.crt");
    }
}
