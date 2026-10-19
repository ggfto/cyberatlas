package io.github.ggfto.atlas.hub.layout;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.github.ggfto.atlas.hub.Tokens;
import io.github.ggfto.atlas.hub.TopologyCollector;
import io.github.ggfto.atlas.hub.TopologyService;
import io.github.ggfto.atlas.hub.TopologyStream;
import io.github.ggfto.atlas.hub.store.LayoutStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Layout manual (design §3.2): sem "salvar o canvas"; cada no e um PATCH com If-Match. Rev divergente = 409 com o
 * valor atual; dois PATCH concorrentes na mesma rev = um vence e o outro leva 409. O reconciliador nunca escreve.
 */
@QuarkusTest
@TestProfile(FixtureProfile.class)
class LayoutTest {

    @Inject
    LayoutStore store;

    @Inject
    TopologyService topology;

    @Inject
    TopologyStream stream;

    static String key() {
        return "docker:vps/" + UUID.randomUUID();
    }

    static String path(String key) {
        return "/api/layout/nodes/" + key;
    }

    static Response patch(String key, String ifMatch, double x, double y) {
        var r = given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).body(Map.of("x", x, "y", y));
        if (ifMatch != null) {
            r = r.header("If-Match", ifMatch);
        }
        return r.when().patch(path(key));
    }

    @Test
    void createThenUpdateWithTheRevAndReadBack() {
        String k = key();
        patch(k, "\"0\"", 10, 20).then().statusCode(200).header("ETag", "\"1\"").body("rev", is(1)).body("updatedBy", is("bia"));
        patch(k, "\"1\"", 30, 40).then().statusCode(200).header("ETag", "\"2\"").body("x", is(30.0f));
        given().auth().oauth2(Tokens.viewer()).when().get("/api/layout").then().statusCode(200)
                .body("find { it.nodeKey == '" + k + "' }.rev", is(2));
    }

    @Test
    void staleRevIs409WithTheCurrentValue() {
        String k = key();
        patch(k, "\"0\"", 1, 1).then().statusCode(200);
        patch(k, "\"1\"", 2, 2).then().statusCode(200);
        patch(k, "\"1\"", 3, 3).then().statusCode(409).body("current.rev", is(2)).body("current.x", is(2.0f));
        // criar de novo o que ja existe tambem e conflito
        patch(k, "\"0\"", 3, 3).then().statusCode(409).body("current.rev", is(2));
        // atualizar o que nao existe: conflito sem valor atual
        patch(key(), "\"5\"", 3, 3).then().statusCode(409).body("current", nullValue());
    }

    @Test
    void twoConcurrentPatchesOnTheSameRevOneWinsOneGets409() throws Exception {
        String k = key();
        patch(k, "\"0\"", 0, 0).then().statusCode(200);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> rs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                double x = i;
                rs.add(pool.submit((Callable<Integer>) () -> {
                    go.await();
                    return patch(k, "\"1\"", x, x).statusCode();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : rs) {
                codes.add(f.get());
            }
            codes.sort(null);
            assertEquals(List.of(200, 409), codes);
            assertEquals(2, store.get(k).orElseThrow().rev());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ifMatchIsRequiredAndValuesAreChecked() {
        patch(key(), null, 1, 1).then().statusCode(428);
        patch(key(), "\"abc\"", 1, 1).then().statusCode(400);
        given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).header("If-Match", "\"0\"").body("{\"x\":1}")
                .when().patch(path(key())).then().statusCode(400);
        patch(key(), "\"0\"", 1e9, 1).then().statusCode(400);
        patch("x".repeat(600), "\"0\"", 1, 1).then().statusCode(400);
    }

    @Test
    void viewerReadsButDoesNotMoveNodes() {
        given().auth().oauth2(Tokens.viewer()).contentType(ContentType.JSON).header("If-Match", "\"0\"").body(Map.of("x", 1, "y", 1))
                .when().patch(path(key())).then().statusCode(403);
        given().when().get("/api/layout").then().statusCode(401);
    }

    @Test
    void batchHasAResultPerItemNeverAllOrNothing() {
        String a = key();
        String b = key();
        patch(b, "\"0\"", 0, 0).then().statusCode(200);
        given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON)
                .body(List.of(Map.of("nodeKey", a, "rev", 0, "x", 5, "y", 5), Map.of("nodeKey", b, "rev", 0, "x", 6, "y", 6)))
                .when().patch("/api/layout/nodes").then().statusCode(200)
                .body("[0].status", is(200)).body("[0].position.rev", is(1))
                .body("[1].status", is(409)).body("[1].current.rev", is(1));
        assertEquals(5.0, store.get(a).orElseThrow().x());
    }

    @Test
    void deleteGoesBackToAutomaticLayoutWithIfMatch() {
        String k = key();
        patch(k, "\"0\"", 0, 0).then().statusCode(200);
        given().auth().oauth2(Tokens.admin()).header("If-Match", "\"7\"").when().delete(path(k)).then().statusCode(409);
        given().auth().oauth2(Tokens.admin()).header("If-Match", "\"1\"").when().delete(path(k)).then().statusCode(204);
        assertFalse(store.get(k).isPresent());
    }

    /** O reconciliador (coleta, merge, stream) nunca escreve layout: coletar e atualizar o stream nao muda a linha. */
    @Test
    void reconcilerNeverTouchesLayout() throws Exception {
        String k = "service:vps/homeassistant";
        store.delete(k, store.get(k).map(LayoutStore.Position::rev).orElse(0L));
        patch(k, "\"0\"", 123, 456).then().statusCode(200);
        LayoutStore.Position before = store.get(k).orElseThrow();
        stream.refresh();
        topology.snapshot();
        Thread.sleep(50);
        stream.refresh();
        assertEquals(before, store.get(k).orElseThrow());
        // e nem pode: nenhuma peca do reconciliador recebe o LayoutStore
        for (Class<?> c : List.of(TopologyService.class, TopologyCollector.class, TopologyStream.class)) {
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                for (Class<?> p : ctor.getParameterTypes()) {
                    assertFalse(LayoutStore.class.isAssignableFrom(p), c.getSimpleName() + " recebe LayoutStore");
                }
            }
            for (Field f : c.getDeclaredFields()) {
                assertFalse(LayoutStore.class.isAssignableFrom(f.getType()), c.getSimpleName() + "." + f.getName());
            }
        }
    }
}
