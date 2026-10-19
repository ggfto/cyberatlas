package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.LogRequest;
import io.github.ggfto.atlas.core.LogSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Logs do Docker pelo socket: id pela listagem (nunca inspect), follow pelo GET de logs da allowlist. */
class DockerLogSourceTest {

    static final String ID = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
    static final String LIST = "/containers/json?all=1";
    static final String LOGS = "/containers/" + ID + "/logs?follow=1&stdout=1&stderr=1&timestamps=1&tail=200";

    static String body() {
        return new String(DockerLogDemuxTest.concat(
                DockerLogDemuxTest.frame(1, "2026-10-19T12:00:00Z pronto\n"),
                DockerLogDemuxTest.frame(2, "2026-10-19T12:00:01Z erro\n")), StandardCharsets.ISO_8859_1);
    }

    @Test
    void followsByIdResolvedFromTheListing(@TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(
                LIST, new FakeDockerSocket.Reply(200, "[{\"Id\":\"" + ID + "\",\"Names\":[\"/9router\"],\"State\":\"running\"}]", false),
                LOGS, new FakeDockerSocket.Reply(200, body(), true, true)))) {
            DockerLogSource src = new DockerLogSource("vps", new DockerClient(fake.socket, Duration.ofSeconds(5)));
            assertTrue(src.handles("docker:vps/9router"));
            assertFalse(src.handles("docker:host/9router"));
            List<LogSource.RawLine> lines = new CopyOnWriteArrayList<>();
            CountDownLatch end = new CountDownLatch(1);
            try (AutoCloseable h = src.follow(new LogRequest("docker:vps/9router", null, 200, true), lines::add, end::countDown)) {
                assertTrue(end.await(5, TimeUnit.SECONDS));
            }
            assertEquals(List.of("pronto", "erro"), lines.stream().map(LogSource.RawLine::text).toList());
            assertEquals(List.of("stdout", "stderr"), lines.stream().map(LogSource.RawLine::stream).toList());
            assertEquals(List.of(LIST, LOGS), fake.requested);
        }
    }

    @Test
    void unknownContainerIsAnError(@TempDir Path dir) throws Exception {
        try (FakeDockerSocket fake = new FakeDockerSocket(dir, Map.of(LIST, new FakeDockerSocket.Reply(200, "[]", false)))) {
            DockerLogSource src = new DockerLogSource("vps", new DockerClient(fake.socket, Duration.ofSeconds(5)));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> src.follow(new LogRequest("docker:vps/sumiu", null, 10, true), l -> { }, () -> { }));
            assertEquals("container nao encontrado: docker:vps/sumiu", e.getMessage());
        }
    }
}
