package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Docker pelo socket local, so leitura (design §1.2): allowlist de 3 GETs no codigo; inspect (traz Env) nunca. */
class DockerSourceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    static final String LIST = "/containers/json?all=1";

    static String fixture() throws IOException {
        try (InputStream in = DockerSourceTest.class.getResourceAsStream("/fixtures/docker-containers.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void containersBecomeNodesWithRealState(@TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(LIST, new FakeDockerSocket.Reply(200, fixture(), true)))) {
            Fragment f = new DockerSource("vps", new DockerClient(fake.socket, Duration.ofSeconds(5)), CLOCK, Duration.ofSeconds(90)).collect();
            Map<String, State> st = f.observations().stream().collect(Collectors.toMap(Observation::nodeKey, Observation::state));
            assertEquals(Map.of(
                    "docker:vps/9router", State.ONLINE,
                    "docker:vps/authentik-worker-1", State.DEGRADED,
                    "docker:vps/registronic", State.OFFLINE,
                    "docker:vps/mosquitto", State.DEGRADED,
                    "docker:vps/novo", State.DEGRADED,
                    "docker:vps/criado", State.OFFLINE), st);
            Map<String, String> why = f.observations().stream().collect(Collectors.toMap(Observation::nodeKey, Observation::reason));
            assertEquals("unhealthy: Up 4 weeks (unhealthy)", why.get("docker:vps/authentik-worker-1"));
            assertEquals("Exited (1) 3 hours ago", why.get("docker:vps/registronic"));
            assertEquals("docker", f.source());
        }
    }

    @Test
    void attributesAreTheRuntimeFactsAndLabelsOnlyComposeOnes(@TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(LIST, new FakeDockerSocket.Reply(200, fixture(), false)))) {
            Fragment f = new DockerSource("vps", new DockerClient(fake.socket, Duration.ofSeconds(5)), CLOCK, Duration.ofSeconds(90)).collect();
            Node n = f.nodes().stream().filter(x -> x.key().equals("docker:vps/9router")).findFirst().orElseThrow();
            assertEquals(Map.of("image", "ghcr.io/ggfto/9router:sha-abc", "state", "running", "status", "Up 2 hours (healthy)",
                    "health", "healthy", "compose_project", "9router", "compose_service", "9router"), n.attributes());
            assertFalse(f.toString().contains("segredo"), "Command nunca entra");
            assertFalse(f.toString().contains("basicauth"), "label do traefik nunca entra");
            assertEquals("vps", n.machine());
        }
    }

    @Test
    void onlyTheListIsRequestedNeverInspect(@TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(LIST, new FakeDockerSocket.Reply(200, fixture(), true)))) {
            new DockerSource("vps", new DockerClient(fake.socket, Duration.ofSeconds(5)), CLOCK, Duration.ofSeconds(90)).collect();
            assertEquals(List.of(LIST), fake.requested);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/containers/a1b2c3d4e5f6/json", "/containers/9router/json", "/images/json", "/containers/a1b2c3d4e5f6/exec",
        "/exec/x/start", "/containers/json/../a1b2c3d4e5f6/json", "/info", "/containers/a1b2c3d4e5f6/logs/../json",
        "/containers/json?all=1&x=/../../exec", "/v1.43/containers/a1b2c3d4e5f6/json"})
    void forbiddenEndpointsNeverReachTheSocket(String path, @TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of())) {
            DockerClient client = new DockerClient(fake.socket, Duration.ofSeconds(5));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> client.get(path));
            assertEquals("endpoint do docker fora da allowlist: " + path, e.getMessage());
            assertEquals(List.of(), fake.requested);
        }
    }

    @Test
    void allowedEndpointsAreTheThreeGets(@TempDir Path dir) throws Exception {
        String id = "a1b2c3d4e5f6";
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(
                "/containers/json?all=1", new FakeDockerSocket.Reply(200, "[]", false),
                "/events?since=1&until=2", new FakeDockerSocket.Reply(200, "", false),
                "/containers/" + id + "/logs?stdout=1&stderr=1&tail=10", new FakeDockerSocket.Reply(200, "", false)))) {
            DockerClient client = new DockerClient(fake.socket, Duration.ofSeconds(5));
            client.get("/containers/json?all=1");
            client.get("/events?since=1&until=2");
            client.get("/containers/" + id + "/logs?stdout=1&stderr=1&tail=10");
            assertEquals(3, fake.requested.size());
        }
    }

    @Test
    void dockerErrorIsAnErrorNotAnEmptyMachine(@TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(LIST, new FakeDockerSocket.Reply(500, "{\"message\":\"x\"}", false)))) {
            DockerSource src = new DockerSource("vps", new DockerClient(fake.socket, Duration.ofSeconds(5)), CLOCK, Duration.ofSeconds(90));
            IOException e = assertThrows(IOException.class, src::collect);
            assertTrue(e.getMessage().startsWith("docker GET /containers/json?all=1: HTTP 500"), e.getMessage());
        }
    }

    @Test
    void missingSocketFailsTheCollect(@TempDir Path dir) {
        DockerSource src = new DockerSource("vps", new DockerClient(dir.resolve("nao-existe.sock"), Duration.ofSeconds(1)), CLOCK,
                Duration.ofSeconds(90));
        assertThrows(IOException.class, src::collect);
    }

    @Test
    void chunkedMultibyteBodyIsDecodedByBytes(@TempDir Path dir) throws Exception {
        // chunks de 100 bytes cortam no meio dos caracteres acentuados: contar por caractere quebraria o JSON
        String body = "[{\"Names\":[\"/acao\"],\"Image\":\"x:1\",\"State\":\"exited\",\"Status\":\""
                + "Saída ção ".repeat(30) + "\",\"Labels\":{}}]";
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(LIST, new FakeDockerSocket.Reply(200, body, true)))) {
            Fragment f = new DockerSource("vps", new DockerClient(fake.socket, Duration.ofSeconds(5)), CLOCK, Duration.ofSeconds(90)).collect();
            assertEquals("Saída ção ".repeat(30), f.nodes().get(0).attributes().get("status"));
        }
    }
}
