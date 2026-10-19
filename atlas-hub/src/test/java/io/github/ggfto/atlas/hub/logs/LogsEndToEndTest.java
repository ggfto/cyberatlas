package io.github.ggfto.atlas.hub.logs;

import io.github.ggfto.atlas.hub.Tokens;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.LogLine;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.hub.TopologyStreamTest;
import io.github.ggfto.atlas.hub.edge.EdgePkiProfile;
import io.github.ggfto.atlas.protocol.Backoff;
import io.github.ggfto.atlas.protocol.EdgeCredentials;
import io.github.ggfto.atlas.protocol.EdgeLink;
import io.github.ggfto.atlas.protocol.EnrollmentClient;
import io.github.ggfto.atlas.protocol.Frame;
import io.github.ggfto.atlas.protocol.ProtocolVersion;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Log ponta a ponta (design §6 F4): o browser pede o log de um container pelo SSE; o hub abre UM follow no edge da
 * maquina (mesmo com dois browsers), o edge manda lotes com credito, e a linha chega redigida aos dois.
 */
@QuarkusTest
@TestProfile(EdgePkiProfile.class)
class LogsEndToEndTest {

    @TestHTTPResource("/api/logs/stream")
    URI logs;

    static final URI EDGE = URI.create("https://localhost:8444");
    static final URI WS = URI.create("wss://localhost:8444/edge/v1");

    /** Edge de teste: responde ao LogOpen mandando um lote quando ganha credito. */
    static final class TestEdge {
        final List<Frame.LogOpen> opens = new CopyOnWriteArrayList<>();
        final AtomicReference<EdgeLink> link = new AtomicReference<>();
        final BlockingQueue<Frame.Welcome> welcome = new LinkedBlockingQueue<>();
        volatile boolean sent;

        EdgeLink connect(EdgeCredentials creds, String machine) throws Exception {
            EdgeLink l = new EdgeLink(WS, creds.sslContext(), () -> new Frame.Hello("edge-" + machine, ProtocolVersion.CURRENT, machine,
                    List.of("docker"), Map.of()), new Backoff(Duration.ofMillis(200), Duration.ofSeconds(1), () -> 0.0),
                    Duration.ofSeconds(5), Duration.ofHours(1), new EdgeLink.Handler() {
                        public void onWelcome(Frame.Welcome w) {
                            welcome.add(w);
                        }

                        public void onLogOpen(Frame.LogOpen o) {
                            opens.add(o);
                        }

                        public void onCredit(Frame.Credit c) {
                            if (!sent) {
                                sent = true;
                                link.get().send(new Frame.LogLines(c.streamId(), 1, List.of(
                                        new LogLine(Instant.now(), "stdout", "conectou com token=abc123", false),
                                        new LogLine(Instant.now(), "stderr", "linha normal", false)), 0));
                            }
                        }
                    });
            link.set(l);
            l.start();
            return l;
        }
    }

    static EdgeCredentials enroll(String machine) throws Exception {
        String token = given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).body(Map.of("machine", machine)).when().post("/api/edges").then()
                .statusCode(201).extract().path("token");
        return EnrollmentClient.enroll(EDGE, token, Files.readString(EdgePkiProfileAccess.ca()), Duration.ofSeconds(5));
    }

    static void container(EdgeLink link, String machine, String name) {
        link.send(new Frame.Snapshot("docker", 1, new Fragment("docker", Instant.now(), Duration.ofSeconds(90),
                List.of(new Node("docker:" + machine + "/" + name, NodeKind.CONTAINER, name, machine, Map.of())), List.of(), List.of())));
    }

    @Test
    void twoBrowsersOneFollowRedactedLines() throws Exception {
        TestEdge edge = new TestEdge();
        try (EdgeLink link = edge.connect(enroll("lab-logs"), "lab-logs")) {
            assertTrue(edge.welcome.poll(10, TimeUnit.SECONDS) != null);
            container(link, "lab-logs", "app");
            Thread.sleep(300);
            URI uri = URI.create(logs + "?node=docker:lab-logs/app&tail=50");
            BlockingQueue<TopologyStreamTest.Event> a = TopologyStreamTest.open(uri, null, Tokens.admin());
            BlockingQueue<TopologyStreamTest.Event> b = TopologyStreamTest.open(URI.create(uri + "&filter=normal"), null, Tokens.admin());
            TopologyStreamTest.Event first = a.poll(10, TimeUnit.SECONDS);
            assertEquals("line", first.name());
            assertTrue(first.data().contains("token=[redacted:kv]"), first.data());
            assertEquals("linha normal", textOf(b.poll(10, TimeUnit.SECONDS).data()));
            assertEquals(1, edge.opens.size(), "um follow so no edge para dois browsers");
            assertEquals("docker:lab-logs/app", edge.opens.get(0).nodeKey());
            assertEquals(50, edge.opens.get(0).tail());
        }
    }

    @Test
    void edgeGoingAwayEndsTheStreamWithTheReason() throws Exception {
        TestEdge edge = new TestEdge();
        EdgeLink link = edge.connect(enroll("lab-cai"), "lab-cai");
        assertTrue(edge.welcome.poll(10, TimeUnit.SECONDS) != null);
        container(link, "lab-cai", "app");
        Thread.sleep(300);
        BlockingQueue<TopologyStreamTest.Event> s = TopologyStreamTest.open(URI.create(logs + "?node=docker:lab-cai/app"), null, Tokens.admin());
        assertEquals("line", s.poll(10, TimeUnit.SECONDS).name());
        link.close();
        TopologyStreamTest.Event end = null;
        for (int i = 0; i < 5 && (end == null || !"end".equals(end.name())); i++) {
            end = s.poll(10, TimeUnit.SECONDS);
        }
        assertEquals("end", end.name());
        assertTrue(end.data().contains("edge desconectado"), end.data());
        // e depois disso o alvo existe no mapa, mas o edge nao: 503
        given().auth().oauth2(Tokens.admin()).when().get("/api/logs/stream?node=docker:lab-cai/app").then().statusCode(503);
    }

    @Test
    void badRequestsAreRefusedBeforeTouchingAnEdge() {
        given().auth().oauth2(Tokens.admin()).when().get("/api/logs/stream?node=docker:nao/existe").then().statusCode(400);
        given().auth().oauth2(Tokens.admin()).when().get("/api/logs/stream?node=route:ha.gf2.in").then().statusCode(400);
        given().auth().oauth2(Tokens.admin()).when().get("/api/logs/stream").then().statusCode(400);
    }

    static String textOf(String json) {
        int i = json.indexOf("\"text\":\"") + 8;
        return json.substring(i, json.indexOf('"', i));
    }
}
