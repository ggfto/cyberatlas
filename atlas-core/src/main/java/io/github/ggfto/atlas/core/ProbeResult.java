package io.github.ggfto.atlas.core;

/**
 * Resultado cru de um GET sem seguir redirect: status e Location, ou o erro de rede; em HTTPS, quantos dias faltam
 * para o certificado do servidor vencer ({@code null} em HTTP ou sem sessao TLS).
 */
public record ProbeResult(Integer status, String location, String error, Integer certDaysLeft) {

    public ProbeResult(Integer status, String location, String error) {
        this(status, location, error, null);
    }
}
