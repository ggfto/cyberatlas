package io.github.ggfto.atlas.hub.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Recurso cuja escrita nao passa pela checagem anti-CSRF do {@link CsrfGuard}. So para rota que NUNCA e autenticada
 * por cookie de sessao (ex.: o enrollment do edge, que se prova pelo token no corpo e so existe na porta de edge).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CsrfExempt {
}
