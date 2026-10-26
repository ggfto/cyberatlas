package io.github.ggfto.atlas.hub.auth;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Politica das paginas do SPA (issue #20). Igual a {@code viewer} (atlas-viewers ou atlas-admins), com uma excecao:
 * a navegacao de iframe ({@code Sec-Fetch-Dest: iframe}, GET/HEAD) recebe o shell do SPA mesmo sem sessao. O 302
 * do code flow para o Authentik morre dentro da moldura (o Authentik manda {@code X-Frame-Options: DENY}, e assim
 * deve continuar), entao o /embed tem de carregar para mostrar o botao "Entrar no atlas", que faz o login num popup
 * top-level.
 * <p>
 * Nao abre dado nenhum: o shell e o index.html, igual para todo mundo, e o codigo do front e publico. Os dados ficam
 * em {@code /api}, que tem permissao propria e sem sessao responde 401 (AtlasAuthMechanism.programmatic). Quem
 * enquadra continua decidido pela CSP: {@code frame-ancestors} so libera o HA no /embed (SecurityHeaders). A regra
 * vale por header, e nao por caminho, porque o Quinoa serve o /embed fazendo reroute para "/", e a permissao roda
 * de novo no caminho trocado. Fora de moldura ({@code Sec-Fetch-Dest: document}) nada muda: sem sessao, 302.
 */
@ApplicationScoped
public class FramedShellPolicy implements HttpSecurityPolicy {

    public static final String NAME = "viewer-or-framed-shell";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext rc, Uni<SecurityIdentity> identity, AuthorizationRequestContext ctx) {
        if (framedNavigation(rc)) {
            return CheckResult.permit();
        }
        return identity.map(id -> id.hasRole(Roles.VIEWER) || id.hasRole(Roles.ADMIN) ? CheckResult.PERMIT : CheckResult.DENY);
    }

    static boolean framedNavigation(RoutingContext rc) {
        HttpMethod m = rc.request().method();
        return (HttpMethod.GET.equals(m) || HttpMethod.HEAD.equals(m))
                && "iframe".equalsIgnoreCase(rc.request().getHeader("Sec-Fetch-Dest"));
    }
}
