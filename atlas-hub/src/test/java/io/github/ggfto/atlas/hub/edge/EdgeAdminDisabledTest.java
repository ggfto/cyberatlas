package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.Tokens;
import static io.restassured.RestAssured.given;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Sem PKI nem admin configurados (o padrao), nao ha como criar edge nem se registrar. */
@QuarkusTest
@TestProfile(EdgeAdminDisabledTest.Off.class)
class EdgeAdminDisabledTest {

    public static class Off extends FixtureProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            var c = new java.util.HashMap<>(super.getConfigOverrides());
            c.put("atlas.edges.admin-enabled", "false");
            return c;
        }
    }

    @Test
    void adminAndEnrollmentAreClosedByDefault() {
        given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).body(Map.of("machine", "vps")).when().post("/api/edges").then().statusCode(404);
        // negado pela EdgePortPolicy; com o mecanismo mTLS ativo (client-auth=request) o DENY a anonimo sai 401
        given().contentType(ContentType.JSON).body(Map.of("token", "x", "csr", "x")).when().post("/api/enrollments").then()
                .statusCode(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403)));
    }
}
