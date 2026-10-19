package io.github.ggfto.atlas.hub.logs;

import static io.restassured.RestAssured.given;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** Logs desligados por padrao ate a F5 trazer login e o papel atlas-admins: as rotas nem existem. */
@QuarkusTest
@TestProfile(FixtureProfile.class)
class LogsDisabledTest {

    @Test
    void logsAreOffByDefault() {
        given().when().get("/api/logs/stream?node=docker:vps/9router").then().statusCode(404);
        given().when().get("/api/logs/targets").then().statusCode(404);
    }
}
