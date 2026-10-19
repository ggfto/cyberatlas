package io.github.ggfto.atlas.hub;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Cabecalhos de seguranca emitidos pelo proprio hub (issue #18), sem depender do Traefik. A CSP vai como header HTTP
 * porque {@code frame-ancestors} em {@code <meta>} e ignorado pelo browser. So em resposta HTML (a pagina e quem
 * pode ser enquadrada); {@code nosniff} em toda resposta. No dev (Quinoa + HMR do Vite, que precisa de inline/eval)
 * a CSP fica de fora, como era com a meta so no build.
 */
@ApplicationScoped
public class SecurityHeaders {

    static final String CSP_BASE = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "base-uri 'self'",
            "object-src 'none'",
            "form-action 'self'");

    /** Ninguem enquadra o atlas. A futura rota /embed (issue #19) tera o seu proprio frame-ancestors aqui. */
    static final String FRAME_ANCESTORS_NONE = "frame-ancestors 'none'";

    static final String CSP = CSP_BASE + "; " + FRAME_ANCESTORS_NONE;

    void register(@Observes Filters filters) {
        boolean csp = LaunchMode.current() != LaunchMode.DEVELOPMENT;
        filters.register(rc -> {
            rc.addHeadersEndHandler(v -> apply(rc, csp));
            rc.next();
        }, 100);
    }

    static void apply(RoutingContext rc, boolean csp) {
        var h = rc.response().headers();
        h.set("X-Content-Type-Options", "nosniff");
        String type = h.get("Content-Type");
        if (csp && type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
            h.set("Content-Security-Policy", cspFor(rc.normalizedPath()));
        }
    }

    /** Ponto unico de decisao da CSP por rota (hoje e uma so). */
    static String cspFor(String path) {
        return CSP;
    }
}
