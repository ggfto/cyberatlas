package io.github.ggfto.atlas.hub.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.ggfto.atlas.hub.Tokens;
import io.github.ggfto.atlas.protocol.Csr;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.oidc.server.OidcWireMock;
import io.quarkus.test.oidc.server.OidcWiremockTestResource;
import io.restassured.filter.cookie.CookieFilter;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Anti-CSRF das escritas (issue #18): sessao de admin de verdade (code flow no Authentik de mentira) e as escritas
 * forjadas que um {@code *.gf2.in} qualquer conseguiria mandar com o cookie dela.
 */
@QuarkusTest
@TestProfile(BreakGlassProfile.class)
class CsrfTest {

    static final String ATLAS = "https://atlas.gf2.in";
    static final String EVIL = "https://evil.gf2.in";
    static final String ADMIN_CODE = "codigo-de-admin-do-atlas";

    @OidcWireMock
    WireMockServer idp;

    @Inject
    DataSource db;

    @Inject
    io.github.ggfto.atlas.hub.store.AuditLog audit;

    CookieFilter session;

    /** Login pelo code flow com um ID token de atlas-admins (o stub padrao do wiremock so da groups user,admin). */
    @BeforeEach
    void loginAsAdmin() {
        idp.stubFor(post(urlEqualTo("/auth/realms/quarkus/token")).withRequestBody(containing("code=" + ADMIN_CODE)).atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"access_token\":\""
                        + Tokens.admin() + "\",\"refresh_token\":\"r\",\"id_token\":\""
                        + OidcWiremockTestResource.getIdToken("bia", Set.of("atlas-admins"), "quarkus-web-app") + "\"}")));
        session = new CookieFilter();
        String authorize = given().filter(session).redirects().follow(false).accept(ContentType.HTML).when().get("/")
                .then().statusCode(302).extract().header("Location");
        Map<String, String> q = Arrays.stream(URI.create(authorize).getRawQuery().split("&")).map(p -> p.split("=", 2))
                .collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8), (a, b) -> a));
        given().filter(session).redirects().follow(false).queryParam("state", q.get("state")).queryParam("code", ADMIN_CODE)
                .when().get(q.get("redirect_uri")).then().statusCode(302);
        // a sessao e de admin mesmo: sem isto, um 403 de papel passaria por 403 de CSRF
        given().filter(session).when().get("/api/audit").then().statusCode(200);
    }

    RequestSpecification asSession() {
        return given().filter(session).redirects().follow(false);
    }

    String newEdge(String machine) {
        return given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).body(Map.of("machine", machine))
                .when().post("/api/edges").then().statusCode(201).extract().path("edgeId");
    }

    String status(String edgeId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("select status from edge where id = ?")) {
            ps.setString(1, edgeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void sessionRevokeFromAForeignOriginWithoutTheHeaderIs403AndTheEdgeStays() throws Exception {
        String id = newEdge("csrf-evil");
        asSession().header("Origin", EVIL).when().post("/api/edges/" + id + "/revoke").then().statusCode(403);
        assertEquals("pending", status(id));
    }

    /** O ataque da issue: {@code <form method=post>} auto-submetido, sem header proprio e sem Origin util. */
    @Test
    void formStylePostWithoutOriginOrHeaderIs403() throws Exception {
        String id = newEdge("csrf-form");
        asSession().contentType(ContentType.URLENC).formParam("x", "1").when().post("/api/edges/" + id + "/revoke")
                .then().statusCode(403);
        assertEquals("pending", status(id));
    }

    /** O header sozinho nao basta: com Origin estrangeira continua 403 (segunda camada, se um dia ligarem CORS). */
    @Test
    void headerWithAForeignOriginIsStill403() throws Exception {
        String id = newEdge("csrf-header-evil");
        asSession().header(CsrfGuard.HEADER, "1").header("Origin", EVIL).when().post("/api/edges/" + id + "/revoke")
                .then().statusCode(403);
        assertEquals("pending", status(id));
    }

    @Test
    void headerAndTheAtlasOriginPass() throws Exception {
        String id = newEdge("csrf-ok");
        asSession().header(CsrfGuard.HEADER, "1").header("Origin", ATLAS).when().post("/api/edges/" + id + "/revoke")
                .then().statusCode(204);
        assertEquals("revoked", status(id));
    }

    @Test
    void withoutOriginTheRefererOfTheAtlasPasses() throws Exception {
        String id = newEdge("csrf-referer");
        asSession().header(CsrfGuard.HEADER, "1").header("Referer", ATLAS + "/mapa").when().post("/api/edges/" + id + "/revoke")
                .then().statusCode(204);
        assertEquals("revoked", status(id));
    }

    /** Todas as escritas do hub, com cookie e Origin estrangeira: nenhuma passa. */
    @Test
    void everyWriteEndpointRefusesAForgedSessionRequest() {
        List<Function<RequestSpecification, io.restassured.response.Response>> writes = List.of(
                r -> r.contentType(ContentType.JSON).header("If-Match", "\"0\"").body(Map.of("x", 1, "y", 2)).patch("/api/layout/nodes/csrf:k"),
                r -> r.contentType(ContentType.JSON).body(List.of()).patch("/api/layout/nodes"),
                r -> r.header("If-Match", "\"0\"").delete("/api/layout/nodes/csrf:k"),
                r -> r.contentType(ContentType.JSON).body(Map.of("machine", "csrf-criado")).post("/api/edges"),
                r -> r.post("/api/edges/edge-qualquer/re-enroll"),
                r -> r.post("/api/edges/edge-qualquer/revoke"));
        for (var w : writes) {
            var resp = w.apply(asSession().header("Origin", EVIL));
            assertEquals(403, resp.statusCode(), resp.asString());
            assertTrue(resp.asString().contains("escrita recusada"), resp.asString());
        }
    }

    @Test
    void bearerNeedsNoHeader() throws Exception {
        String id = newEdge("csrf-bearer");
        given().auth().oauth2(Tokens.admin()).when().post("/api/edges/" + id + "/revoke").then().statusCode(204);
        assertEquals("revoked", status(id));
    }

    @Test
    void edgeEnrollmentNeedsNoHeader() throws Exception {
        String token = given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).body(Map.of("machine", "csrf-enroll"))
                .when().post("/api/edges").then().statusCode(201).extract().path("token");
        given().relaxedHTTPSValidation().contentType(ContentType.JSON)
                .body(Map.of("token", token, "csr", Csr.pem(Csr.newKeyPair(), "edge-csrf-enroll")))
                .when().post(SecurityTest.TLS + "/api/enrollments").then().statusCode(200).body("edgeId", is("edge-csrf-enroll"));
    }

    @Test
    void breakGlassBasicOnTheTlsPortNeedsNoHeader() throws Exception {
        String id = newEdge("csrf-breakglass");
        given().relaxedHTTPSValidation().auth().preemptive().basic(BreakGlassProfile.USER, BreakGlassProfile.PASSWORD)
                .when().post(SecurityTest.TLS + "/api/edges/" + id + "/revoke").then().statusCode(204);
        assertEquals("revoked", status(id));
    }

    @Test
    void refusalIsAudited() {
        asSession().header("Origin", EVIL).when().post("/api/edges/edge-auditado/revoke").then().statusCode(403)
                .body("error", containsString("X-Atlas-Csrf"));
        var actions = audit.recent(50).stream().map(e -> e.actor() + " " + e.action()).toList();
        assertTrue(actions.stream().anyMatch(a -> a.endsWith(" csrf-recusado") && !a.startsWith("anonimo")), actions.toString());
    }
}
