package io.github.ggfto.atlas.hub;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/** Cloudflare + Argo no mapa servido, contra as APIs falsas (sem credencial real). */
@QuarkusTest
@TestProfile(SourcesProfile.class)
class SourcesTest {

    @Test
    void tunnelGivesTheStateOfTheMachineEntry() {
        given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then().statusCode(200)
                .body("nodes.find { it.node.key == 'entry:vps/tunel' }.status.state", is("ONLINE"))
                .body("nodes.find { it.node.key == 'entry:host/tunel' }.status.state", is("OFFLINE"))
                .body("nodes.find { it.node.key == 'cf-tunnel:vps-tunel' }.node.attributes.connections", is("2"))
                .body("edges.id", hasItem("routes_to:cf-tunnel:vps-tunel->route:ha.gf2.in"));
    }

    @Test
    void driftFromDnsAndArgoShowsUp() {
        given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then().statusCode(200)
                .body("nodes.find { it.node.key == 'dns:orfao.gf2.in' }.drift", hasItem("dns-sem-servico"))
                .body("nodes.find { it.node.key == 'route:logs.gf2.in' }.drift", hasItem("servico-sem-dns"))
                .body("nodes.find { it.node.key == 'argo:vps-dozzle' }.drift", hasItem("outofsync"))
                .body("nodes.node.key", hasItems("dns:ha.gf2.in", "argo:vps-dozzle"));
    }

    @Test
    void payloadCarriesNoIpIdOrToken() {
        String json = given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then().statusCode(200).extract().asString();
        for (String leak : new String[] {"198.51.100.77", SourcesProfile.ZONE_ID, SourcesProfile.ACCOUNT, SourcesProfile.T_VPS,
                SourcesProfile.T_HOST, SourcesProfile.CF_TOKEN, SourcesProfile.ARGO_TOKEN, "kubernetes.default.svc", "cfargotunnel"}) {
            assertFalse(json.contains(leak), "payload vazou " + leak);
        }
    }

    @Test
    void tokensWentAsBearer() throws Exception {
        assertTrue(SourcesProfile.api().auth.contains("Bearer " + SourcesProfile.CF_TOKEN));
        assertTrue(SourcesProfile.api().auth.contains("Bearer " + SourcesProfile.ARGO_TOKEN));
    }
}
