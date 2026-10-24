package io.github.ggfto.atlas.hub.mcp;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.Sanitizer;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.hub.TopologyService;
import io.github.ggfto.atlas.hub.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Criterio da issue #26: nenhuma tool devolve atributo fora da allowlist do Sanitizer, e o texto livre (motivo,
 * historico, erro de fonte) sai com IP mascarado e segredo redigido. Semeia um no com atributo proibido, IP num
 * atributo permitido e segredo no motivo, e chama todas as tools.
 */
@QuarkusTest
@TestProfile(McpProdLikeProfile.class)
class McpSanitizerTest {

    static final String KEY = "docker:vps/vazador";
    static final String IP = "10.20.30.40";
    static final String SECRET = "hunter2-segredo";
    static final String[] LEAKS = {IP, SECRET, McpProdLikeProfile.BROKEN_IP, "\"Env\"", "SENHA_DO_BANCO", "\"tunnel_id\"",
            "tun-0000-segredo", "\"lan_ip\"", "eyJhbGciOiJIUzI1NiJ9"};

    @Inject
    TopologyService topology;

    @Inject
    DataSource db;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();
        Node leaky = new Node(KEY, NodeKind.CONTAINER, "vazador", "vps", Map.of(
                "image", "nginx:latest",
                "status", "Up 2 hours, " + IP + ":8080->80/tcp",
                "Env", "SENHA_DO_BANCO=" + SECRET,
                "tunnel_id", "tun-0000-segredo",
                "lan_ip", IP));
        String reason = "dial tcp " + IP + ":80: connection refused; password=" + SECRET
                + " Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbmEifQ.c2lnbmF0dXJhLWZhbHNh";
        topology.accept(new Fragment("edge:vps/mcp-teste", now, Duration.ofHours(1), List.of(leaky), List.of(),
                List.of(new Observation(KEY, "edge:vps/mcp-teste", State.OFFLINE, reason, now, Duration.ofHours(1)))));
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "insert into status_change (node_key, from_state, to_state, reason, at) values (?, 'ONLINE', 'OFFLINE', ?, ?)")) {
            ps.setString(1, KEY);
            ps.setString(2, reason);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    @Test
    void noToolReturnsAnAttributeOutsideTheAllowlist() {
        Mcp mcp = Mcp.open(Tokens.viewer());
        Response topo = ok(mcp.call("get_topology", "{}"));
        Response node = ok(mcp.call("get_node", "{\"key\":\"" + KEY + "\"}"));
        Response problems = ok(mcp.call("list_problems", "{}"));
        Response history = ok(mcp.call("get_history", "{\"key\":\"" + KEY + "\"}"));
        Response sources = ok(mcp.call("list_sources", "{}"));

        // o no semeado aparece (senao a checagem abaixo passaria no vazio)
        topo.then().body("result.structuredContent.nodes.key", hasItem(KEY));
        problems.then().body("result.structuredContent.problems.key", hasItem(KEY));
        node.then().body("result.structuredContent.node.attributes.image", is("nginx:latest"))
                .body("result.structuredContent.node.attributes.status", is("Up 2 hours, <ip>:8080->80/tcp"));

        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.addAll(topo.jsonPath().getList("result.structuredContent.nodes"));
        nodes.add(node.jsonPath().getMap("result.structuredContent.node"));
        nodes.addAll(problems.jsonPath().getList("result.structuredContent.problems"));
        assertTrue(nodes.size() > 10, "poucos nos para a checagem: " + nodes.size());
        for (Map<String, Object> n : nodes) {
            NodeKind kind = NodeKind.valueOf((String) n.get("kind"));
            @SuppressWarnings("unchecked")
            Map<String, String> attrs = (Map<String, String>) n.get("attributes");
            for (String k : attrs.keySet()) {
                boolean allowed = Sanitizer.DEFAULT.sanitize(new Node("x", kind, "x", null, Map.of(k, "v"))).attributes().containsKey(k);
                assertTrue(allowed, "atributo fora da allowlist: " + kind + "." + k + " em " + n.get("key"));
            }
        }

        for (Response r : List.of(topo, node, problems, history, sources)) {
            String body = r.asString();
            for (String leak : LEAKS) {
                assertFalse(body.contains(leak), "tool vazou " + leak + ": " + body);
            }
        }
    }

    @Test
    void freeTextIsMaskedLikeTheRestOfTheRepo() {
        Mcp mcp = Mcp.open(Tokens.viewer());
        mcp.call("get_node", "{\"key\":\"" + KEY + "\"}").then().statusCode(200)
                .body("result.structuredContent.node.state", is("OFFLINE"))
                .body("result.structuredContent.node.reason", containsString("dial tcp <ip>:80"))
                .body("result.structuredContent.node.reason", containsString("password=[redacted:kv]"))
                .body("result.structuredContent.node.sources.find { it.source == 'edge:vps/mcp-teste' }.reason",
                        containsString("[redacted:"))
                .body("result.structuredContent.node.drift", hasItem("imagem-flutuante"));
        JsonPath h = mcp.call("get_history", "{\"key\":\"" + KEY + "\"}").then().statusCode(200).extract().jsonPath();
        assertTrue(h.getList("result.structuredContent.changes.reason", String.class).stream()
                .anyMatch(s -> s.contains("<ip>") && s.contains("[redacted:")), "historico sem mascara");
        mcp.call("list_sources", "{}").then().statusCode(200)
                .body("result.structuredContent.sources.find { !it.ok }.error", containsString("<ip>"))
                .body("result.structuredContent.sources.find { !it.ok }.error", not(containsString(McpProdLikeProfile.BROKEN_IP)));
    }

    static Response ok(Response r) {
        r.then().statusCode(200).body("result.isError", is(false));
        return r;
    }
}
