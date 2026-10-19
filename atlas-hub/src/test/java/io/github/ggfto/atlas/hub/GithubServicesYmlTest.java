package io.github.ggfto.atlas.hub;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/** services.yml da VPS pelo GitHub e o do host por arquivo, no mesmo hub; token so como Bearer. */
@QuarkusTest
@TestProfile(GithubServicesYmlTest.Github.class)
class GithubServicesYmlTest {

    public static class Github implements QuarkusTestProfile {
        @Override
        public List<TestResourceEntry> testResources() {
            return List.of(new TestResourceEntry(FakeGitHubResource.class));
        }
    }

    @Test
    void vpsFromGithubAndHostFromFileBothOnTheMap() {
        given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then().statusCode(200)
                .body("nodes.node.key", hasItems("route:ha.gf2.in", "machine:vps", "machine:host"));
    }

    @Test
    void tokenWentOnlyAsBearerAndRefIsPinned() {
        assertTrue(FakeGitHubResource.AUTH.contains("Bearer " + FakeGitHubResource.TOKEN), FakeGitHubResource.AUTH.toString());
        assertTrue(FakeGitHubResource.PATHS.stream().allMatch(p -> p.equals("/repos/ggfto/infra/contents/vps/services.yml?ref=stable")),
                FakeGitHubResource.PATHS.toString());
        assertFalse(FakeGitHubResource.PATHS.stream().anyMatch(p -> p.contains(FakeGitHubResource.TOKEN)));
    }

    @Test
    void sourcesShowNoErrorWhenGithubAnswers() {
        given().auth().oauth2(Tokens.viewer()).when().get("/api/sources").then().statusCode(200)
                .body("name", hasItem("servicesyml:vps")).body("find { it.name == 'servicesyml:vps' }.error", org.hamcrest.Matchers.nullValue());
    }
}
