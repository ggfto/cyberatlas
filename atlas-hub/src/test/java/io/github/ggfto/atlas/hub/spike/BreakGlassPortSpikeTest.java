package io.github.ggfto.atlas.hub.spike;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Resultado do spike: a mesma rota, no mesmo processo, e liberada na porta de break-glass e negada na outra,
 * so pela porta local. Detalhe em docs/spikes/policy-por-porta.md.
 */
@QuarkusTest
@TestProfile(BreakGlassPortProfile.class)
class BreakGlassPortSpikeTest {

    @Test
    void publicPortIsDenied() {
        given().when().get("http://localhost:8081/spike/breakglass").then().statusCode(403);
    }

    @Test
    void breakGlassPortIsAllowed() {
        given().relaxedHTTPSValidation().when().get("https://localhost:" + BreakGlassPortProfile.TLS_TEST_PORT + "/spike/breakglass")
                .then().statusCode(200).body(is("breakglass"));
    }

    @Test
    void otherRoutesAreNotAffected() {
        given().when().get("http://localhost:8081/q/health").then().statusCode(200);
    }
}
