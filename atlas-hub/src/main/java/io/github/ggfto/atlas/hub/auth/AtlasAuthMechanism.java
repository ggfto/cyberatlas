package io.github.ggfto.atlas.hub.auth;

import io.github.ggfto.atlas.hub.AtlasConfig;
import io.github.ggfto.atlas.hub.store.AuditLog;
import io.quarkus.oidc.runtime.OidcAuthenticationMechanism;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

/**
 * Autenticacao do hub (design §4), um mecanismo so por cima do OIDC:
 * <ul>
 *   <li>browser: sessao OIDC do Authentik (code flow); API/teste: bearer do mesmo Authentik (modo hybrid);</li>
 *   <li>{@code /mcp} so por bearer: cookie de sessao nunca autentica ali (issue #26);</li>
 *   <li>sem sessao em {@code /api}, {@code /mcp}, SSE ou {@code /edge}: 401, nunca o redirect do code flow (o fetch/EventSource
 *       nao segue login);</li>
 *   <li>Basic so como break-glass e so na porta TLS de edge (porta local da requisicao, provada no spike da F1);
 *       em qualquer outra porta, falha na hora, e a porta publica nunca anuncia Basic.</li>
 * </ul>
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class AtlasAuthMechanism implements HttpAuthenticationMechanism {

    static final String REALM = "Basic realm=\"atlas break-glass\"";

    private final OidcAuthenticationMechanism oidc;
    private final AuditLog audit;
    private final int breakGlassPort;
    private final String user;
    private final String bcrypt;

    AtlasAuthMechanism(OidcAuthenticationMechanism oidc, AuditLog audit, AtlasConfig config) {
        this.oidc = oidc;
        this.audit = audit;
        this.breakGlassPort = config.edges().port();
        this.user = config.breakglass().user().orElse(null);
        this.bcrypt = config.breakglass().bcrypt().orElse(null);
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext ctx, IdentityProviderManager ipm) {
        String h = ctx.request().getHeader("Authorization");
        if (h != null && h.regionMatches(true, 0, "Basic ", 0, 6)) {
            if (!onBreakGlassPort(ctx)) {
                return Uni.createFrom().failure(new AuthenticationFailedException("login local so na porta de break-glass"));
            }
            String[] up = decode(h.substring(6).trim());
            // bcrypt e caro de proposito: fora do event loop
            return Uni.createFrom().item(() -> check(up[0], up[1])).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        }
        if (mcp(ctx) && (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7))) {
            // /mcp so por bearer (issue #26): o transporte MCP e rota Vert.x, o CsrfGuard (JAX-RS) nao roda nele, e o
            // cookie de sessao SameSite=Lax chega de qualquer *.gf2.in. Sessao de browser nunca autentica o MCP.
            return Uni.createFrom().failure(new AuthenticationFailedException("autenticacao requerida"));
        }
        return oidc.authenticate(ctx, ipm);
    }

    /** O endpoint MCP ({@code /mcp} e o SSE legado em {@code /mcp/*}): cliente programatico, so bearer. */
    static boolean mcp(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        return path.equals("/mcp") || path.startsWith("/mcp/");
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext ctx) {
        if (onBreakGlassPort(ctx)) {
            return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", REALM));
        }
        if (programmatic(ctx)) {
            // a spec MCP pede o desafio Bearer no 401; o resto da API segue sem anunciar esquema
            return Uni.createFrom().item(mcp(ctx) ? new ChallengeData(401, "WWW-Authenticate", "Bearer") : new ChallengeData(401, null, null));
        }
        return oidc.getChallenge(ctx);
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return oidc.getCredentialTypes();
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext ctx) {
        return oidc.getCredentialTransport(ctx);
    }

    @Override
    public int getPriority() {
        return oidc.getPriority() + 1;
    }

    private boolean onBreakGlassPort(RoutingContext ctx) {
        return user != null && bcrypt != null && ctx.request().isSSL() && ctx.request().localAddress().port() == breakGlassPort;
    }

    static boolean programmatic(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        String accept = ctx.request().getHeader("Accept");
        return path.startsWith("/api/") || path.startsWith("/edge/") || mcp(ctx) || (accept != null && accept.contains("text/event-stream"));
    }

    private SecurityIdentity check(String name, String password) {
        // compara o usuario em tempo constante e sempre roda o bcrypt, para nao dizer pelo tempo se o usuario existe
        boolean userOk = MessageDigest.isEqual(name.getBytes(StandardCharsets.UTF_8), user.getBytes(StandardCharsets.UTF_8));
        boolean passOk = OpenBSDBCrypt.checkPassword(bcrypt, password.toCharArray());
        if (!(userOk && passOk)) {
            audit.record(name, "login-break-glass-recusado", null);
            throw new AuthenticationFailedException("break-glass recusado");
        }
        audit.record(name, "login-break-glass", null);
        return QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(name)).addRole(Roles.ADMIN)
                .addAttribute("atlas.auth", "break-glass").build();
    }

    private static String[] decode(String b64) {
        try {
            String s = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int i = s.indexOf(':');
            return i < 0 ? new String[] {s, ""} : new String[] {s.substring(0, i), s.substring(i + 1)};
        } catch (IllegalArgumentException e) {
            return new String[] {"", ""};
        }
    }
}
