package io.github.ggfto.atlas.hub.mcp;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/** Cliente MCP minimo dos testes: streamable HTTP, uma sessao por token. */
final class Mcp {

    static final String ACCEPT = "application/json, text/event-stream";
    static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
            + "\"capabilities\":{},\"clientInfo\":{\"name\":\"teste\",\"version\":\"0\"}}}";

    private final String token;
    private final String sid;
    private int id = 10;

    private Mcp(String token, String sid) {
        this.token = token;
        this.sid = sid;
    }

    static RequestSpecification spec(String token, String sid) {
        RequestSpecification s = given().redirects().follow(false).accept(ACCEPT).contentType(ContentType.JSON);
        if (token != null) {
            s = s.auth().oauth2(token);
        }
        return sid == null ? s : s.header("Mcp-Session-Id", sid);
    }

    /** initialize + notifications/initialized com o bearer. */
    static Mcp open(String token) {
        String sid = spec(token, null).body(INIT).when().post("/mcp").then().statusCode(200).extract().header("Mcp-Session-Id");
        spec(token, sid).body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").when().post("/mcp").then().statusCode(202);
        return new Mcp(token, sid);
    }

    Response rpc(String method, String params) {
        return spec(token, sid).body("{\"jsonrpc\":\"2.0\",\"id\":" + (id++) + ",\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}").when().post("/mcp");
    }

    Response tools() {
        return rpc("tools/list", null);
    }

    Response call(String tool, String arguments) {
        return rpc("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}");
    }
}
