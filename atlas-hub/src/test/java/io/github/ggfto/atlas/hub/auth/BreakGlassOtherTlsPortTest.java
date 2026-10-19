package io.github.ggfto.atlas.hub.auth;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TLS sozinho nao basta para o break-glass: numa porta TLS que NAO e a de edge (ex.: quarkus.http.ssl-port diferente
 * de atlas.edges.port), o Basic com a senha certa e recusado e a porta nao anuncia Basic.
 */
@QuarkusTest
@TestProfile(BreakGlassOtherTlsPortTest.EdgeElsewhere.class)
class BreakGlassOtherTlsPortTest {

    /** TLS na 8444 (porta de teste), mas a porta de edge/break-glass configurada e outra. */
    public static class EdgeElsewhere implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> c = new HashMap<>(new BreakGlassProfile().getConfigOverrides());
            c.put("atlas.edges.port", "9443");
            return c;
        }
    }

    static final String TLS = "https://localhost:8444";

    @Test
    void basicIsRefusedOnATlsPortThatIsNotTheEdgePort() {
        given().relaxedHTTPSValidation().auth().preemptive().basic(BreakGlassProfile.USER, BreakGlassProfile.PASSWORD)
                .when().get(TLS + "/api/topology").then().statusCode(401);
    }

    @Test
    void thatPortGoesToTheIdpInsteadOfAskingForBasic() {
        given().relaxedHTTPSValidation().redirects().follow(false).accept(ContentType.HTML).when().get(TLS + "/").then().statusCode(302);
    }
}
