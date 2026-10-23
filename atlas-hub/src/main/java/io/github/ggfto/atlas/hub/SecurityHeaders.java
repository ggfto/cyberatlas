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

    /** Ninguem enquadra o atlas, salvo a visao so leitura /embed. */
    static final String FRAME_ANCESTORS_NONE = "frame-ancestors 'none'";

    static final String CSP = CSP_BASE + "; " + FRAME_ANCESTORS_NONE;

    /**
     * Unica origem que enquadra o /embed (issue #19): o Home Assistant, origem exata e so https. Sem
     * {@code X-Frame-Options: ALLOW-FROM}, que e obsoleto; o Traefik do atlas tambem nao manda DENY (atlas-headers).
     */
    static final String EMBED_ANCESTOR = "https://ha.gf2.in";

    static final String CSP_EMBED = CSP_BASE + "; frame-ancestors " + EMBED_ANCESTOR;

    void register(@Observes Filters filters) {
        boolean csp = LaunchMode.current() != LaunchMode.DEVELOPMENT;
        filters.register(rc -> {
            // O SPA routing do Quinoa faz reroute("/") para servir o index.html em /embed: o filtro roda de novo,
            // com o caminho ja trocado e sem os headers-end handlers da 1a passada (os dados do contexto ficam). A
            // CSP decide pelo caminho que o cliente pediu, guardado na 1a passada; o handler e registrado em toda
            // passada, e todos (se sobrar mais de um) decidem igual.
            if (rc.get(REQUESTED_PATH) == null) {
                rc.put(REQUESTED_PATH, rc.request().path());
            }
            rc.addHeadersEndHandler(v -> apply(rc, csp));
            rc.next();
        }, 100);
    }

    private static final String REQUESTED_PATH = "atlas.requested-path";

    static void apply(RoutingContext rc, boolean csp) {
        var h = rc.response().headers();
        h.set("X-Content-Type-Options", "nosniff");
        String type = h.get("Content-Type");
        if (csp && type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith("text/html")) {
            h.set("Content-Security-Policy", cspFor(rc.get(REQUESTED_PATH)));
        }
    }

    /** Ponto unico de decisao da CSP por rota: so o /embed e enquadravel, e so pelo HA. */
    static String cspFor(String path) {
        return isEmbed(path) ? CSP_EMBED : CSP;
    }

    /**
     * Caminho cru (sem query) e so as duas formas exatas, a mesma regra do isEmbedPath do atlas-web. O caminho que
     * chega aqui ja pode ter sido normalizado pelo Traefik ({@code //embed} vira /embed); por isso o front, alem
     * desta regra, monta o Embed (nunca o App) sempre que estiver enquadrado (shouldRenderEmbed).
     */
    static boolean isEmbed(String path) {
        return "/embed".equals(path) || "/embed/".equals(path);
    }
}
