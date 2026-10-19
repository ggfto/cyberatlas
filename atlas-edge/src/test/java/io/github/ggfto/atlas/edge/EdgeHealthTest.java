package io.github.ggfto.atlas.edge;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EdgeHealthTest {

    @Test
    void healthIsUp() {
        given().when().get("/q/health").then().statusCode(200).body("status", is("UP"));
    }

    @Test
    void servesNoWebUi() {
        // o edge nao tem front nem API publica: so o healthcheck
        given().when().get("/").then().statusCode(404);
    }
}
