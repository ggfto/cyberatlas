package io.github.ggfto.atlas.hub;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import java.util.Set;

/**
 * Tokens do Authentik de mentira (wiremock do quarkus-test-oidc-server, no lugar do Authentik real): o claim
 * {@code groups} vira papel, como o Authentik manda. A anotacao liga o wiremock para todos os testes do hub.
 */
@QuarkusTestResource(OidcWiremockTestResource.class)
public final class Tokens {

    private Tokens() {
    }

    public static String viewer() {
        return OidcWiremockTestResource.getAccessToken("ana", Set.of("atlas-viewers"));
    }

    public static String admin() {
        return OidcWiremockTestResource.getAccessToken("bia", Set.of("atlas-admins"));
    }

    /** Conta do IdP sem nenhum grupo do atlas (o Homelable deixaria entrar). */
    public static String noGroup() {
        return OidcWiremockTestResource.getAccessToken("caio", Set.of("outro-app"));
    }
}
