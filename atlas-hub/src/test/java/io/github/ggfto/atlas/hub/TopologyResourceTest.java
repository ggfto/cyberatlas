package io.github.ggfto.atlas.hub;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(FixtureProfile.class)
class TopologyResourceTest {

    @Test
    void servesTheDeclaredMapOfBothMachines() {
        given().when().get("/api/topology").then().statusCode(200)
                .body("nodes.node.key", hasItems("machine:host", "machine:vps", "traefik:host", "traefik:vps",
                        "entry:host/lan", "entry:vps/tunel", "authgate:authentik",
                        "route:fx.local.gf2.in", "route:ha.gf2.in", "service:vps/homeassistant"))
                .body("edges.find { it.id == 'auth_via:route:logs.gf2.in->authgate:authentik' }.kind", is("AUTH_VIA"))
                .body("nodes.find { it.node.key == 'service:vps/homeassistant' }.node.attributes.backend", is("http://homeassistant:8123"));
    }

    @Test
    void withoutChecksNothingIsInventedOnline() {
        given().when().get("/api/topology").then().statusCode(200)
                .body("nodes.status.state", everyItem(is("UNKNOWN")))
                .body("nodes.status.reason", everyItem(is("sem fonte")));
    }

    @Test
    void payloadCarriesNoSecretOfTheFiles() {
        String json = given().when().get("/api/topology").then().statusCode(200).extract().asString();
        for (String leak : new String[] {"192.0.2.10", "198.51.100.20", "00000000-0000-0000-0000-00000000", "tunnel_id",
                "lan_ip", "public_ip", "managed_by", "auth_bypass_paths"}) {
            assertFalse(json.contains(leak), "payload vazou " + leak);
        }
    }
}
