package io.github.ggfto.atlas.hub;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Repo privado sem acesso (404): o hub sobe, o resto do mapa segue e o erro aparece em /api/sources, sem token. */
@QuarkusTest
@TestProfile(GithubDownTest.Down.class)
class GithubDownTest {

    public static class Down implements QuarkusTestProfile {
        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(FakeGitHubResource.class, Map.of("mode", "404")));
        }
    }

    @Inject
    TopologyService topology;

    @Test
    void hubStaysUpAndTheOtherSourcesKeepWorking() {
        given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then().statusCode(200)
                .body("nodes.node.key", hasItem("machine:host")).body("nodes.node.key", not(hasItem("route:ha.gf2.in")));
    }

    @Test
    void theErrorIsVisibleAndCarriesNoToken() {
        given().auth().oauth2(Tokens.viewer()).when().get("/api/sources").then().statusCode(200)
                .body("find { it.name == 'servicesyml:vps' }.error", containsString("repo privado"))
                .body("find { it.name == 'servicesyml:vps' }.error", not(containsString(FakeGitHubResource.TOKEN)));
        given().when().get("/api/sources").then().statusCode(401);
    }

    @Test
    void edgeWelcomeDoesNotBreakWhenTheFileIsUnreachable() {
        assertEquals(List.of(), topology.localTargets("vps"));
    }
}
