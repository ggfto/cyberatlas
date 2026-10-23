package io.github.ggfto.atlas.hub.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.ggfto.atlas.hub.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWireMock;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.filter.cookie.CookieFilter;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sessao de browser nunca autentica o /mcp (issue #26). O transporte MCP e rota Vert.x: o CsrfGuard do #29 nao roda
 * nele, entao a unica barreira contra um {@code *.gf2.in} qualquer usando o cookie e o AtlasAuthMechanism exigir
 * bearer. No spike, sem isso, os dois casos abaixo davam 200 com host 0.0.0.0 (como em producao).
 */
@QuarkusTest
@TestProfile(McpProdLikeProfile.class)
class McpCookieTest {

    static final String CODE = "codigo-de-viewer-do-mcp";

    @OidcWireMock
    WireMockServer idp;

    CookieFilter session;

    @BeforeEach
    void loginAsViewer() {
        idp.stubFor(post(urlEqualTo("/auth/realms/quarkus/token")).withRequestBody(containing("code=" + CODE)).atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"access_token\":\""
                        + Tokens.viewer() + "\",\"refresh_token\":\"r\",\"id_token\":\""
                        + OidcWiremockTestResource.getIdToken("ana", Set.of("atlas-viewers"), "quarkus-web-app") + "\"}")));
        session = new CookieFilter();
        String authorize = given().filter(session).redirects().follow(false).accept(ContentType.HTML).when().get("/")
                .then().statusCode(302).extract().header("Location");
        Map<String, String> q = Arrays.stream(URI.create(authorize).getRawQuery().split("&")).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8), (a, b) -> a));
        given().filter(session).redirects().follow(false).queryParam("state", q.get("state")).queryParam("code", CODE)
                .when().get(q.get("redirect_uri")).then().statusCode(302);
        // a sessao vale mesmo: sem isto, um 401 de sessao quebrada passaria por 401 da guarda
        given().filter(session).when().get("/api/topology").then().statusCode(200);
    }

    @Test
    void sessionCookieFromAnotherSubdomainIs401() {
        given().filter(session).redirects().follow(false).header("Origin", "https://evil.gf2.in").accept(Mcp.ACCEPT)
                .contentType(ContentType.JSON).body(Mcp.INIT).when().post("/mcp").then().statusCode(401)
                .header("Location", nullValue()).header("Mcp-Session-Id", nullValue());
    }

    /** A forma de um {@code <form enctype=text/plain>}: sem Origin (navegador velho/Referrer-Policy) e sem preflight. */
    @Test
    void sessionCookieWithoutOriginAsPlainTextIs401() {
        given().filter(session).redirects().follow(false).accept(Mcp.ACCEPT).contentType("text/plain").body(Mcp.INIT)
                .when().post("/mcp").then().statusCode(401)
                .header("Location", nullValue()).header("Mcp-Session-Id", nullValue());
    }
}
