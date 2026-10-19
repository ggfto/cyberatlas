package io.github.ggfto.atlas.hub;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import io.quarkiverse.quinoa.testing.QuinoaTestProfiles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

// O Quinoa vem desligado em teste; este perfil liga e roda o `npm test` (Vitest) do atlas-web no build.
@QuarkusTest
@TestProfile(QuinoaTestProfiles.EnableAndRunTests.class)
class HubHealthTest {

    @Test
    void healthIsUp() {
        given().when().get("/q/health").then().statusCode(200).body("status", is("UP"));
    }

    @Test
    void servesTheWebBundle() {
        // o index do Vite chega pelo Quinoa, com a CSP de producao injetada no build
        given().when().get("/").then().statusCode(200)
                .body(containsString("<div id=\"root\"></div>"))
                .body(containsString("Content-Security-Policy"));
    }
}
