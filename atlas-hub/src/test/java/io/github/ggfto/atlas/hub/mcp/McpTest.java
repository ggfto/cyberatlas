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

    /**
     * A falha de seguranca no /mcp sai no formato JSON-RPC, mas com mensagem neutra: nem nome de classe Java nem a
     * mensagem interna da excecao (a do break-glass diz onde fica o login local). Status, desafio e ausencia de
     * Location continuam os mesmos.
     */
    @Test
    void securityFailureDoesNotExposeTheException() {
        String[][] cases = {{null, "401"}, {"Bearer lixo", "401"}, {"Basic eDp5", "401"}, {"Bearer " + Tokens.noGroup(), "403"}};
        for (String[] c : cases) {
            var req = given().redirects().follow(false).accept(Mcp.ACCEPT).contentType(ContentType.JSON).body(Mcp.INIT);
            if (c[0] != null) {
                req.header("Authorization", c[0]);
            }
            var r = req.when().post("/mcp").then().statusCode(Integer.parseInt(c[1])).header("Location", nullValue())
                    .body("jsonrpc", is("2.0")).body("id", is(1)).body("error.code", is(-32005))
                    .body("error.message", is(c[1].equals("401") ? "Unauthorized" : "Forbidden"));
            if (c[1].equals("401")) {
                r.header("WWW-Authenticate", org.hamcrest.Matchers.equalToIgnoringCase("Bearer"));
            }
        }
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

    /** Bearer sem cookie/sessão: profile.groups da conta de serviço vira VIEWER. */
    @Test
    void serviceAccountListsProblemsWithoutSession() {
        Mcp.open(Tokens.viewer()).call("list_problems", "{}").then().statusCode(200)
                .body("result.isError", is(false))
                .body("result.structuredContent.unknown", notNullValue());
        // Mutação do claim: remover grupo impede até tools/call, sem sessão fallback.
        Mcp.spec(Tokens.noGroup(), null)
                .body("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_problems\",\"arguments\":{}}}")
                .when().post("/mcp").then().statusCode(403);
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
                .body("result.structuredContent.node.state", notNullValue())
                .body("result.structuredContent.node.sources", notNullValue())
                .body("result.structuredContent.edges", not(org.hamcrest.Matchers.empty()));
        // fixture sem checks: UNKNOWN sem drift nao vira problema, mas a contagem aparece. Outros testes do mesmo
        // hub podem ter empurrado fragmento de edge (drift), entao a regra e conferida item a item.
        java.util.List<java.util.Map<String, Object>> problems = mcp.call("list_problems", "{}").then().statusCode(200)
                .body("result.isError", is(false))
                .body("result.structuredContent.unknown", org.hamcrest.Matchers.greaterThan(0))
                .extract().jsonPath().getList("result.structuredContent.problems");
        for (java.util.Map<String, Object> p : problems) {
            boolean down = "OFFLINE".equals(p.get("state")) || "DEGRADED".equals(p.get("state"));
            org.junit.jupiter.api.Assertions.assertTrue(down || !((java.util.List<?>) p.get("drift")).isEmpty(),
                    "problema sem estado ruim nem drift: " + p.get("key"));
        }
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
