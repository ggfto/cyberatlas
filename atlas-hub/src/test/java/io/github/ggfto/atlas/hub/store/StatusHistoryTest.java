package io.github.ggfto.atlas.hub.store;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.NodeStatus;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.TopologyNode;
import io.github.ggfto.atlas.hub.FixtureProfile;
import io.github.ggfto.atlas.hub.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Historico minimo (design §3.3): so transicoes, retencao de 7 dias, leitura pela API para a gaveta. */
@QuarkusTest
@TestProfile(FixtureProfile.class)
class StatusHistoryTest {

    @Inject
    StatusHistory history;

    @Inject
    DataSource db;

    static TopologyNode tn(String key, State s, String reason) {
        return new TopologyNode(new Node(key, NodeKind.SERVICE, key, "vps", Map.of()), new NodeStatus(s, reason, List.of()), List.of());
    }

    @Test
    void onlyTransitionsAreRecordedNotTheFirstSightingNorRepeats() {
        String k = "service:vps/" + UUID.randomUUID();
        history.observe(List.of(tn(k, State.ONLINE, "200")));
        history.observe(List.of(tn(k, State.ONLINE, "200")));
        history.observe(List.of(tn(k, State.OFFLINE, "502")));
        history.observe(List.of(tn(k, State.OFFLINE, "502 de novo")));
        history.observe(List.of(tn(k, State.ONLINE, "200")));
        List<StatusHistory.Change> h = history.of(k, 10);
        assertEquals(2, h.size());
        assertEquals(new StatusHistory.Change(k, "OFFLINE", "ONLINE", "200", h.get(0).at()), h.get(0));
        assertEquals("ONLINE", h.get(1).from());
        assertEquals("502", h.get(1).reason());
    }

    @Test
    void aNodeThatDisappearsAndComesBackIsNotAChange() {
        String k = "service:vps/" + UUID.randomUUID();
        history.observe(List.of(tn(k, State.ONLINE, "200")));
        history.observe(List.of());
        history.observe(List.of(tn(k, State.ONLINE, "200")));
        assertEquals(0, history.of(k, 10).size());
    }

    @Test
    void retentionDropsWhatIsOlderThanSevenDays() throws Exception {
        String k = "service:vps/" + UUID.randomUUID();
        Instant now = Instant.now();
        try (var c = db.getConnection();
             var ps = c.prepareStatement("insert into status_change (node_key, from_state, to_state, reason, at) values (?, 'ONLINE', 'OFFLINE', 'x', ?)")) {
            for (Duration age : List.of(Duration.ofDays(8), Duration.ofDays(6))) {
                ps.setString(1, k);
                ps.setTimestamp(2, Timestamp.from(now.minus(age)));
                ps.executeUpdate();
            }
        }
        history.purge();
        assertEquals(1, history.of(k, 10).size());
    }

    @Test
    void viewerReadsTheHistoryOfANode() {
        String k = "service:vps/" + UUID.randomUUID();
        history.observe(List.of(tn(k, State.ONLINE, "200")));
        history.observe(List.of(tn(k, State.DEGRADED, "lento")));
        given().auth().oauth2(Tokens.viewer()).queryParam("node", k).when().get("/api/history").then().statusCode(200)
                .body("[0].to", is("DEGRADED")).body("[0].reason", is("lento"));
        given().when().queryParam("node", k).get("/api/history").then().statusCode(401);
    }
}
