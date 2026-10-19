package io.github.ggfto.atlas.core;

/** Resultado cru de um GET sem seguir redirect: status e Location, ou o erro de rede. */
public record ProbeResult(Integer status, String location, String error) {
}
