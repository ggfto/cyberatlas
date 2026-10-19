package io.github.ggfto.atlas.core;

/** Estado de um no. {@code UNKNOWN} nunca vence uma resposta: so aparece quando nenhuma fonte fresca decide. */
public enum State {
    ONLINE(0), DEGRADED(1), OFFLINE(2), UNKNOWN(-1);

    private final int severity;

    State(int severity) {
        this.severity = severity;
    }

    /** Gravidade para o "pior vence"; {@code UNKNOWN} fica fora da comparacao. */
    int severity() {
        return severity;
    }
}
