package io.github.ggfto.atlas.hub.logs;

import io.github.ggfto.atlas.hub.Tokens;
import static io.restassured.RestAssured.given;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** Chave de desligar o log (atlas.logs.enabled=false): nem admin chega nas rotas. */
@QuarkusTest
@TestProfile(LogsDisabledTest.Off.class)
class LogsDisabledTest {

    public static class Off extends FixtureProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            var c = new java.util.HashMap<>(super.getConfigOverrides());
            c.put("atlas.logs.enabled", "false");
            return c;
        }
    }

    @Test
    void logsCanBeSwitchedOff() {
        given().auth().oauth2(Tokens.admin()).when().get("/api/logs/stream?node=docker:vps/9router").then().statusCode(404);
        given().auth().oauth2(Tokens.admin()).when().get("/api/logs/targets").then().statusCode(404);
    }
}
