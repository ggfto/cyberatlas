package io.github.ggfto.atlas.hub.spike;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SPIKE (design §4): a policy enxerga a porta LOCAL em que a requisicao chegou? Se sim, o login local de
 * break-glass fica restrito a porta que so escuta no WG (8443) e a porta publica (8080, atras do Traefik) so
 * aceita OIDC. Vive so em src/test: nada disso vai para producao antes da F5.
 */
@ApplicationScoped
public class BreakGlassPortPolicy implements HttpSecurityPolicy {

    @ConfigProperty(name = "spike.breakglass-port", defaultValue = "-1")
    int breakGlassPort;

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request, Uni<SecurityIdentity> identity,
                                            AuthorizationRequestContext requestContext) {
        int local = request.request().localAddress().port();
        return Uni.createFrom().item(local == breakGlassPort ? CheckResult.PERMIT : CheckResult.DENY);
    }

    @Override
    public String name() {
        return "breakglass-port";
    }
}
