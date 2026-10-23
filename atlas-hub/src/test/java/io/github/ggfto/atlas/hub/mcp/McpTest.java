package io.github.ggfto.atlas.hub.mcp;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.github.ggfto.atlas.hub.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/** O /mcp no hub (issue #26): JVM aqui, binario nativo no McpIT (criterio "funciona no nativo"). */
@QuarkusTest
@TestProfile(FixtureProfile.class)
class McpTest {

    static final String[] TOOLS = {"get_topology", "get_node", "list_problems", "get_history", "list_sources"};

    /** Sem Authorization: 401 com desafio Bearer, nunca o 302 do code flow, qualquer que seja o Accept. */
    @Test
    void withoutTokenIs401NeverTheLoginRedirect() {
        for (String accept : new String[] {Mcp.ACCEPT, "application/json", "text/html"}) {
            given().redirects().follow(false).accept(accept).contentType(ContentType.JSON).body(Mcp.INIT)
                    .when().post("/mcp").then().statusCode(401)
                    .header("Location", nullValue())
                    .header("WWW-Authenticate", is("Bearer"));
        }
        given().redirects().follow(false).accept("text/event-stream").when().get("/mcp/sse").then().statusCode(401)
                .header("Location", nullValue());
    }

    @Test
    void tokenWithoutAtlasGroupIs403() {
        Mcp.spec(Tokens.noGroup(), null).body(Mcp.INIT).when().post("/mcp").then().statusCode(403);
    }

    @Test
    void viewerListsOnlyTheReadOnlyTools() {
        Mcp.open(Tokens.viewer()).tools().then().statusCode(200)
                .body("result.tools.name", containsInAnyOrder(TOOLS))
                .body("result.tools.annotations.readOnlyHint", everyItem(is(true)))
                .body("result.tools.annotations.destructiveHint", everyItem(is(false)));
    }

    @Test
    void adminAlsoGetsIn() {
        Mcp.open(Tokens.admin()).tools().then().statusCode(200).body("result.tools.name", hasItem("list_problems"));
    }

    @Test
    void toolsAnswerFromTheSameMapAsTheRestApi() {
        Mcp mcp = Mcp.open(Tokens.viewer());
        mcp.call("get_topology", "{}").then().statusCode(200).body("result.isError", is(false))
                .body("result.structuredContent.nodes.key", hasItem("service:vps/homeassistant"))
                .body("result.structuredContent.edges.kind", hasItem("AUTH_VIA"));
        mcp.call("get_node", "{\"key\":\"service:vps/homeassistant\"}").then().statusCode(200).body("result.isError", is(false))
                .body("result.structuredContent.node.attributes.backend", is("http://homeassistant:8123"))
                .body("result.structuredContent.node.state", is("UNKNOWN"))
                .body("result.structuredContent.node.reason", is("sem fonte"))
                .body("result.structuredContent.edges", not(org.hamcrest.Matchers.empty()));
        // fixture sem checks: tudo UNKNOWN, nada vira problema, mas a contagem aparece
        mcp.call("list_problems", "{}").then().statusCode(200).body("result.isError", is(false))
                .body("result.structuredContent.problems", org.hamcrest.Matchers.empty())
                .body("result.structuredContent.unknown", org.hamcrest.Matchers.greaterThan(0));
        mcp.call("get_history", "{\"key\":\"service:vps/homeassistant\"}").then().statusCode(200).body("result.isError", is(false))
                .body("result.structuredContent.key", is("service:vps/homeassistant"))
                .body("result.structuredContent.changes", notNullValue());
        mcp.call("list_sources", "{}").then().statusCode(200).body("result.isError", is(false))
                .body("result.structuredContent.sources.name", hasItems("servicesyml:host", "servicesyml:vps"));
    }

    @Test
    void unknownNodeIsAToolErrorNotAnException() {
        Mcp.open(Tokens.viewer()).call("get_node", "{\"key\":\"nao:existe\"}").then().statusCode(200)
                .body("result.isError", is(true));
    }
}
