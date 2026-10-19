package io.github.ggfto.atlas.protocol;

import java.util.Optional;

/** Versao do protocolo. O hub aceita a atual e a anterior; fora disso o edge recebe {@code Reject} com a janela. */
public final class ProtocolVersion {

    public static final int CURRENT = 1;

    private ProtocolVersion() {
    }

    public static Optional<String> rejection(int offered, int current) {
        int oldest = Math.max(1, current - 1);
        if (offered >= oldest && offered <= current) {
            return Optional.empty();
        }
        return Optional.of("versao de protocolo " + offered + " fora da janela [" + oldest + ", " + current + "]");
    }
}
