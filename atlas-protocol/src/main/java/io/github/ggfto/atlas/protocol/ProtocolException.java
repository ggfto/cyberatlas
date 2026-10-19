package io.github.ggfto.atlas.protocol;

/** Frame invalido, desconhecido ou grande demais: a sessao e encerrada, nunca "melhor esforco". */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProtocolException(String message) {
        super(message);
    }
}
