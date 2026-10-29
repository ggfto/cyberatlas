package io.github.ggfto.atlas.hub.mcp;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkus.security.AuthenticationException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * 401/403 do {@code /mcp} sem a excecao no corpo. O handler de falha da extensao MCP (quarkus-mcp-server-http 2.0.3,
 * {@code HttpMcpServerRecorder.createAuthFailureHandler}) responde o JSON-RPC com {@code failure.toString()}: nome
 * da classe Java e a mensagem interna (a do break-glass diz onde fica o login local). Este handler roda antes dele,
 * com o mesmo status, o mesmo formato e o mesmo codigo, e mensagem neutra; o desafio {@code WWW-Authenticate} ja
 * foi posto pela autenticacao e nao muda. Outras falhas seguem para o handler da extensao.
 */
@ApplicationScoped
public class McpSecurityFailure {

    void register(@Observes Router router) {
        router.route("/mcp").order(Integer.MIN_VALUE).failureHandler(McpSecurityFailure::handle);
    }

    static void handle(RoutingContext ctx) {
        Throwable failure = ctx.failure();
        if (!(failure instanceof AuthenticationException || failure instanceof UnauthorizedException
                || failure instanceof ForbiddenException)) {
            ctx.next();
            return;
        }
        boolean forbidden = failure instanceof ForbiddenException;
        ctx.response().setStatusCode(forbidden ? 403 : 401);
        if (!ctx.request().isEnded()) {
            ctx.request().resume();
        }
        // o id da requisicao volta na resposta, como no handler da extensao, para o cliente casar o erro
        ctx.request().body().onComplete(ar -> {
            Object id = null;
            if (ar.succeeded() && ar.result() != null && ar.result().length() > 0) {
                try {
                    id = new JsonObject(ar.result()).getValue("id");
                } catch (RuntimeException e) {
                    // corpo que nao e JSON: responde sem id
                }
            }
            JsonObject error = new JsonObject().put("jsonrpc", "2.0").put("id", id).put("error",
                    new JsonObject().put("code", JsonRpcErrorCodes.SECURITY_ERROR).put("message", forbidden ? "Forbidden" : "Unauthorized"));
            ctx.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json").end(error.toBuffer());
        });
    }
}
