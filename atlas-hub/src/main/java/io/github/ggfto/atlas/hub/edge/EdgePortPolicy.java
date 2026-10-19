package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.AtlasConfig;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Enrollment e o WebSocket dos edges so na porta de edge (a que so escuta no WG/cluster), nunca na publica que o
 * Traefik expoe. Mesma tecnica provada no spike da F1 (docs/spikes/policy-por-porta.md).
 */
@ApplicationScoped
public class EdgePortPolicy implements HttpSecurityPolicy {

    private final int edgePort;

    EdgePortPolicy(AtlasConfig config) {
        this.edgePort = config.edges().port();
    }

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request, Uni<SecurityIdentity> identity,
                                            AuthorizationRequestContext requestContext) {
        boolean ok = request.request().isSSL() && request.request().localAddress().port() == edgePort;
        return Uni.createFrom().item(ok ? CheckResult.PERMIT : CheckResult.DENY);
    }

    @Override
    public String name() {
        return "edge-port";
    }
}
