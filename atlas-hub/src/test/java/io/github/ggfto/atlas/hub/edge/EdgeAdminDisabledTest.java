package io.github.ggfto.atlas.hub.edge;

import static io.restassured.RestAssured.given;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Sem PKI nem admin configurados (o padrao), nao ha como criar edge nem se registrar. */
@QuarkusTest
@TestProfile(FixtureProfile.class)
class EdgeAdminDisabledTest {

    @Test
    void adminAndEnrollmentAreClosedByDefault() {
        given().contentType(ContentType.JSON).body(Map.of("machine", "vps")).when().post("/api/edges").then().statusCode(404);
        given().contentType(ContentType.JSON).body(Map.of("token", "x", "csr", "x")).when().post("/api/enrollments").then().statusCode(403);
    }
}
