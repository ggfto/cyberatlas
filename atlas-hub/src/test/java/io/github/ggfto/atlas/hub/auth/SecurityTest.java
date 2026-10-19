package io.github.ggfto.atlas.hub.auth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.hub.Tokens;
import io.github.ggfto.atlas.hub.logs.LogService;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.github.ggfto.atlas.hub.store.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Aceite de seguranca da F5 (design §4 e §6): sem sessao = 401 em API e SSE; viewer ve o mapa mas nao abre log nem
 * mexe em edge; conta sem grupo do atlas nao entra; login local so na porta de break-glass.
 */
@QuarkusTest
@TestProfile(BreakGlassProfile.class)
class SecurityTest {

    static final String TLS = "https://localhost:8444";

    @Inject
    AuditLog audit;

    @Inject
    LogService logs;

    /**
     * Achado do spike (docs/spikes/policy-por-porta.md): sem a extensao de seguranca as permissoes HTTP da config
     * sao ignoradas em silencio. /api/topology so e protegida pela config (sem anotacao no recurso): se a
     * permissao estiver inativa, isto vira 200.
     */
    @Test
    void httpPermissionFromConfigIsActive() {
        given().when().get("/api/topology").then().statusCode(401);
        given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then().statusCode(200);
    }

    @Test
    void noSessionIs401NotARedirectOnApiAndSse() {
        given().redirects().follow(false).when().get("/api/topology").then().statusCode(401);
        given().redirects().follow(false).accept("text/event-stream").when().get("/api/topology/stream").then().statusCode(401);
        given().redirects().follow(false).when().get("/api/logs/stream?node=docker:vps/x").then().statusCode(401);
    }

    @Test
    void browserWithoutSessionIsSentToTheIdp() {
        given().redirects().follow(false).accept(ContentType.HTML).when().get("/").then().statusCode(302)
                .header("Location", containsString("response_type=code"));
    }

    @Test
    void viewerSeesTheMapButNotLogsEdgesOrAudit() {
        String v = Tokens.viewer();
        given().auth().oauth2(v).when().get("/api/topology").then().statusCode(200);
        given().auth().oauth2(v).when().get("/api/logs/targets").then().statusCode(403);
        given().auth().oauth2(v).when().get("/api/logs/stream?node=docker:vps/x").then().statusCode(403);
        given().auth().oauth2(v).contentType(ContentType.JSON).body(Map.of("machine", "x")).when().post("/api/edges").then().statusCode(403);
        given().auth().oauth2(v).when().get("/api/audit").then().statusCode(403);
    }

    @Test
    void adminReachesLogsAndAudit() {
        given().auth().oauth2(Tokens.admin()).when().get("/api/logs/targets").then().statusCode(200);
        given().auth().oauth2(Tokens.admin()).when().get("/api/audit").then().statusCode(200);
    }

    @Test
    void idpAccountWithoutAtlasGroupIsForbidden() {
        given().auth().oauth2(Tokens.noGroup()).when().get("/api/topology").then().statusCode(403);
    }

    /** Segunda checagem (design §4): mesmo chamado por fora da rota, o LogService nao abre log para viewer. */
    @Test
    void logServiceChecksTheRoleAgainBeforeAnyLogOpen() {
        var viewer = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("ana")).addRole(Roles.VIEWER).build();
        assertThrows(ForbiddenException.class, () -> logs.open("docker:vps/x", null, 10, null, false, viewer));
        assertThrows(ForbiddenException.class, () -> logs.open("docker:vps/x", null, 10, null, false, QuarkusSecurityIdentity.builder()
                .setAnonymous(true).build()));
    }

    @Test
    void healthStaysOpen() {
        given().when().get("/q/health").then().statusCode(200);
    }

    @Test
    void localLoginIsRefusedOnThePublicPortEvenWithTheRightPassword() {
        given().auth().preemptive().basic(BreakGlassProfile.USER, BreakGlassProfile.PASSWORD).when().get("/api/topology")
                .then().statusCode(401);
    }

    @Test
    void localLoginWorksOnlyOnTheBreakGlassPortAndIsAudited() {
        given().relaxedHTTPSValidation().auth().preemptive().basic(BreakGlassProfile.USER, BreakGlassProfile.PASSWORD)
                .when().get(TLS + "/api/logs/targets").then().statusCode(200);
        given().relaxedHTTPSValidation().auth().preemptive().basic(BreakGlassProfile.USER, "errada")
                .when().get(TLS + "/api/topology").then().statusCode(401);
        given().relaxedHTTPSValidation().auth().preemptive().basic("outro", BreakGlassProfile.PASSWORD)
                .when().get(TLS + "/api/topology").then().statusCode(401);
        var actions = audit.recent(50).stream().map(e -> e.actor() + " " + e.action()).toList();
        assertTrue(actions.contains("socorro login-break-glass"), actions.toString());
        assertTrue(actions.contains("socorro login-break-glass-recusado"), actions.toString());
        assertTrue(actions.contains("outro login-break-glass-recusado"), actions.toString());
    }

    @Test
    void breakGlassPortAsksForBasicWhenThereIsNoSession() {
        given().relaxedHTTPSValidation().redirects().follow(false).accept(ContentType.HTML).when().get(TLS + "/").then()
                .statusCode(401).header("WWW-Authenticate", containsString("Basic"));
    }

    @Test
    void publicPortNeverAdvertisesTheLocalLogin() {
        String h = given().redirects().follow(false).when().get("/api/topology").then().statusCode(401).extract().header("WWW-Authenticate");
        assertTrue(h == null || !h.contains("Basic"), h);
    }

    @Test
    void auditListsWhoOpenedWhat() {
        audit.record("bia", "teste", "alvo");
        given().auth().oauth2(Tokens.admin()).when().get("/api/audit").then().statusCode(200)
                .body("actor", hasItem("bia")).body("action", hasItem("teste"));
        assertEquals("bia", audit.recent(1).get(0).actor());
    }
}
