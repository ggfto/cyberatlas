package io.github.ggfto.atlas.hub.edge;

/** Pedido de enrollment/admin de edge recusado; a mensagem vai ao cliente (sem detalhe que ajude a adivinhar). */
public class EnrollmentException extends RuntimeException {

    public EnrollmentException(String message) {
        super(message);
    }
}
