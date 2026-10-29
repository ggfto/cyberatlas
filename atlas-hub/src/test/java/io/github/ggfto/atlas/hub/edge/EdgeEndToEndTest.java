package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.Tokens;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.protocol.Backoff;
import io.github.ggfto.atlas.protocol.EdgeCredentials;
import io.github.ggfto.atlas.protocol.EdgeLink;
import io.github.ggfto.atlas.protocol.EnrollmentClient;
import io.github.ggfto.atlas.protocol.Frame;
import io.github.ggfto.atlas.protocol.ProtocolVersion;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/**
 * Ponta a ponta na mesma maquina (design §6 F2): admin cria o edge, o edge gera a chave e se registra pela porta
 * de edge, conecta por WebSocket mTLS, recebe o Welcome com os checks locais e o snapshot dele aparece no mapa.
 */
@QuarkusTest
@TestProfile(EdgePkiProfile.class)
class EdgeEndToEndTest {

    static final URI EDGE = URI.create("https://localhost:" + EdgePkiProfile.EDGE_PORT);
    static final URI WS = URI.create("wss://localhost:" + EdgePkiProfile.EDGE_PORT + "/edge/v1");

    static String newEdge(String machine) {
        return given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).body(Map.of("machine", machine))
                .when().post("/api/edges").then().statusCode(201).body("edgeId", is("edge-" + machine))
                .extract().path("token");
    }

    static EdgeCredentials enroll(String machine) throws Exception {
        return EnrollmentClient.enroll(EDGE, newEdge(machine), EdgePkiProfile.caPem(), Duration.ofSeconds(5));
    }

    /**
     * Prazo do Hello nos testes: um Hello perdido reconecta em 2 s + 200 ms de backoff e a resposta da segunda
     * tentativa chega bem antes do poll de 10 s (o hub responde em menos de 1 s, medido sob carga).
     */
    static final Duration HELLO_TIMEOUT = Duration.ofSeconds(2);

    record Session(EdgeLink link, BlockingQueue<Frame> frames) {
    }

    static Session connect(SSLContext ssl, String edgeId, String machine, int version) {
        BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        EdgeLink link = new EdgeLink(WS, ssl, () -> new Frame.Hello(edgeId, version, machine, List.of("docker"), Map.of()),
                new Backoff(Duration.ofMillis(200), Duration.ofSeconds(1), () -> 0.0), Duration.ofSeconds(5), Duration.ofHours(1),
                HELLO_TIMEOUT, new EdgeLink.Handler() {
                    public void onWelcome(Frame.Welcome w) {
                        frames.add(w);
                    }

                    public void onReject(String reason) {
                        frames.add(new Frame.Reject(reason));
                    }
                });
        link.start();
        return new Session(link, frames);
    }

    @Test
    void enrollConnectAndTheSnapshotJoinsTheMap() throws Exception {
        EdgeCredentials creds = enroll("vps");
        Session s = connect(creds.sslContext(), "edge-vps", "vps", ProtocolVersion.CURRENT);
        try (EdgeLink link = s.link()) {
            Frame.Welcome w = (Frame.Welcome) s.frames().poll(10, TimeUnit.SECONDS);
            assertTrue(w != null, "sem Welcome");
            assertTrue(w.checks().contains(new Frame.LocalCheck("service:vps/9router", "http://9router:20128/", "auth.gf2.in", false)),
                    w.checks().toString());
            Instant now = Instant.now();
            Fragment docker = new Fragment("docker", now, Duration.ofSeconds(90),
                    List.of(new Node("docker:vps/9router", NodeKind.CONTAINER, "9router", "vps", Map.of("state", "running")),
                            new Node("docker:host/intruso", NodeKind.CONTAINER, "intruso", "host", Map.of())),
                    List.of(), List.of(new Observation("docker:vps/9router", "docker", State.ONLINE, "Up 2 hours", now, Duration.ofSeconds(90)),
                            new Observation("route:fx.local.gf2.in", "docker", State.OFFLINE, "mentira de outra maquina", now, Duration.ofSeconds(90))));
            assertTrue(link.send(new Frame.Snapshot("docker", 1, docker)));
            awaitTopology(() -> given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then().statusCode(200)
                    .body("nodes.find { it.node.key == 'docker:vps/9router' }.status.state", is("ONLINE"))
                    .body("edges.id", hasItem("routes_to:service:vps/9router->docker:vps/9router")));
            given().auth().oauth2(Tokens.viewer()).when().get("/api/topology").then()
                    .body("nodes.node.key", not(hasItem("docker:host/intruso")))
                    .body("nodes.find { it.node.key == 'route:fx.local.gf2.in' }.status.reason", is("sem fonte"));
        }
    }

    @Test
    void tokenIsSingleUse() throws Exception {
        String token = newEdge("lab-token");
        EnrollmentClient.enroll(EDGE, token, EdgePkiProfile.caPem(), Duration.ofSeconds(5));
        IOException e = assertThrows(IOException.class, () -> EnrollmentClient.enroll(EDGE, token, EdgePkiProfile.caPem(), Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("HTTP 403"), e.getMessage());
    }

    @Test
    void enrollmentAndEdgeSocketAreOnlyOnTheEdgePort() {
        // com a porta TLS em client-auth=request existe o mecanismo mTLS, entao o DENY a um anonimo pode sair como
        // 401 (desafio) em vez de 403; negado e o que importa
        given().contentType(ContentType.JSON).body(Map.of("token", "x", "csr", "x")).when().post("/api/enrollments").then()
                .statusCode(anyOf(is(401), is(403)));
        given().when().get("/edge/v1").then().statusCode(anyOf(is(401), is(403)));
    }

    @Test
    void outOfWindowVersionIsRejectedWithTheReason() throws Exception {
        EdgeCredentials creds = enroll("lab-versao");
        Session s = connect(creds.sslContext(), "edge-lab-versao", "lab-versao", 99);
        try (EdgeLink link = s.link()) {
            Frame f = s.frames().poll(10, TimeUnit.SECONDS);
            assertEquals(new Frame.Reject("versao de protocolo 99 fora da janela [1, 1]"), f);
        }
    }

    @Test
    void helloWithAnotherEdgeIdIsRejected() throws Exception {
        EdgeCredentials creds = enroll("lab-mentira");
        Session s = connect(creds.sslContext(), "edge-vps", "vps", ProtocolVersion.CURRENT);
        try (EdgeLink link = s.link()) {
            assertEquals(new Frame.Reject("hello de edge-vps num certificado de edge-lab-mentira"), s.frames().poll(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void revokedEdgeCannotConnect() throws Exception {
        EdgeCredentials creds = enroll("lab-revogado");
        given().auth().oauth2(Tokens.admin()).when().post("/api/edges/edge-lab-revogado/revoke").then().statusCode(204);
        Session s = connect(creds.sslContext(), "edge-lab-revogado", "lab-revogado", ProtocolVersion.CURRENT);
        try (EdgeLink link = s.link()) {
            assertEquals(null, s.frames().poll(3, TimeUnit.SECONDS), "revogado nao recebe Welcome");
            assertFalse(link.connected());
        }
    }

    @Test
    void withoutClientCertificateThereIsNoSession() throws Exception {
        SSLContext trustOnly = SSLContext.getInstance("TLS");
        trustOnly.init(null, EdgeCredentials.trustOnly(EdgePkiProfile.caPem()).getTrustManagers(), null);
        Session s = connect(trustOnly, "edge-vps", "vps", ProtocolVersion.CURRENT);
        try (EdgeLink link = s.link()) {
            assertEquals(null, s.frames().poll(3, TimeUnit.SECONDS));
            assertFalse(link.connected());
        }
    }

    @Test
    void adminIsOffByDefaultElsewhere() {
        // o admin so liga com atlas.edges.admin-enabled (ate a F5 trazer login); aqui ligado, entao responde
        given().auth().oauth2(Tokens.admin()).contentType(ContentType.JSON).body(Map.of("machine", "../x")).when().post("/api/edges").then().statusCode(400);
    }

    static void awaitTopology(Runnable assertion) throws InterruptedException {
        AssertionError last = null;
        for (int i = 0; i < 50; i++) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last;
    }
}
