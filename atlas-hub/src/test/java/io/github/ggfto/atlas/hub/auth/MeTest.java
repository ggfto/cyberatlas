package io.github.ggfto.atlas.hub.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.github.ggfto.atlas.hub.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import java.util.Set;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(FixtureProfile.class)
class MeTest {

    @Test
    void meShowsOnlyTheAtlasRoles() {
        String t = OidcWiremockTestResource.getAccessToken("bia", Set.of("atlas-admins", "financeiro"));
        given().auth().oauth2(t).when().get("/api/me").then().statusCode(200).body("name", is("bia")).body("roles", contains("atlas-admins"));
        given().auth().oauth2(Tokens.viewer()).when().get("/api/me").then().statusCode(200).body("roles", contains("atlas-viewers"));
        given().when().get("/api/me").then().statusCode(401);
    }
}
