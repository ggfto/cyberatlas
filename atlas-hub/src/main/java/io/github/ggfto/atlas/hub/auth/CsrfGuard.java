package io.github.ggfto.atlas.hub.auth;

import io.github.ggfto.atlas.hub.AtlasConfig;
import io.github.ggfto.atlas.hub.store.AuditLog;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.SimpleResourceInfo;

/**
 * Anti-CSRF das escritas do hub (issue #18). O cookie de sessao e {@code SameSite=Lax} e todo {@code *.gf2.in} e
 * same-site com o atlas, entao um {@code <form method=post>} de qualquer subdominio leva o cookie junto. Toda
 * escrita ({@code POST/PUT/PATCH/DELETE}) que chega SEM {@code Authorization} (ou seja, autenticada pelo cookie)
 * precisa de:
 * <ol>
 *   <li>{@code X-Atlas-Csrf: 1}: formulario nao manda header proprio, e fetch cross-origin com ele exige preflight
 *       CORS, que o hub nao responde;</li>
 *   <li>{@code Origin} igual a {@code atlas.public-origin} (sem Origin, a origem do {@code Referer}; sem nenhum dos
 *       dois, recusa). Segunda camada, caso alguem ligue CORS um dia.</li>
 * </ol>
 * Isentas: requisicao com {@code Authorization} (bearer do modo hybrid ou Basic do break-glass: o OIDC so olha o
 * cookie quando nao ha Authorization, e o browser nao poe Authorization proprio cross-origin sem preflight) e recurso
 * marcado {@link CsrfExempt} (enrollment do edge). Filtro JAX-RS pos-matching: pega toda rota de escrita que existir
 * ou vier a existir, roda depois da autenticacao (sem sessao continua 401) e sabe o ator para a auditoria.
 */
public class CsrfGuard {

    public static final String HEADER = "X-Atlas-Csrf";
    static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final String origin;
    private final AuditLog audit;

    CsrfGuard(AtlasConfig config, AuditLog audit) {
        this.origin = normalize(config.publicOrigin());
        this.audit = audit;
    }

    @ServerRequestFilter
    public Response check(ContainerRequestContext ctx, SimpleResourceInfo resource) {
        if (!UNSAFE.contains(ctx.getMethod().toUpperCase(Locale.ROOT)) || ctx.getHeaderString("Authorization") != null
                || exempt(resource)) {
            return null;
        }
        String why = refusal(ctx.getHeaderString(HEADER), ctx.getHeaderString("Origin"), ctx.getHeaderString("Referer"));
        if (why == null) {
            return null;
        }
        var principal = ctx.getSecurityContext() == null ? null : ctx.getSecurityContext().getUserPrincipal();
        String actor = principal == null || principal.getName().isEmpty() ? "anonimo" : principal.getName();
        audit.record(actor, "csrf-recusado", clip(ctx.getMethod() + " " + ctx.getUriInfo().getRequestUri().getRawPath() + " (" + why + ")"));
        return Response.status(403).type(MediaType.APPLICATION_JSON).entity(Map.of("error", "escrita recusada: " + why)).build();
    }

    /** Motivo da recusa, ou null se passa. */
    String refusal(String header, String originHeader, String referer) {
        if (!"1".equals(header)) {
            return "sem " + HEADER;
        }
        String from = originHeader != null ? normalize(originHeader) : originOf(referer);
        if (from == null) {
            return "sem Origin nem Referer";
        }
        if (!origin.equals(from)) {
            return "origem " + from;
        }
        return null;
    }

    private static boolean exempt(SimpleResourceInfo r) {
        return r != null && r.getResourceClass() != null && (r.getResourceClass().isAnnotationPresent(CsrfExempt.class)
                || exemptMethod(r));
    }

    private static boolean exemptMethod(SimpleResourceInfo r) {
        try {
            return r.getResourceClass().getMethod(r.getMethodName(), r.parameterTypes()).isAnnotationPresent(CsrfExempt.class);
        } catch (NoSuchMethodException | RuntimeException e) {
            return false;
        }
    }

    static String originOf(String referer) {
        if (referer == null || referer.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(referer.trim());
            if (u.getScheme() == null || u.getHost() == null) {
                return null;
            }
            return normalize(u.getScheme() + "://" + u.getHost() + (u.getPort() < 0 ? "" : ":" + u.getPort()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String normalize(String o) {
        String s = o.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.replaceFirst("^https://([^/]+):443$", "https://$1").replaceFirst("^http://([^/]+):80$", "http://$1");
    }

    private static String clip(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
