package io.github.ggfto.atlas.adapters.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * services.yml pelo GitHub (contents:read, design §1.3): ref fixa, ETag/If-None-Match (304 = sem custo de rate
 * limit), rate limit respeitado ate o reset, 404 de repo privado como erro visivel, token so no Bearer.
 */
class GitHubFileTest {

    static final String TOKEN = "github_pat_SO-DE-TESTE-123";
    static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    record Reply(int status, String body, Map<String, String> headers, long delayMs) {
        Reply(int status, String body, Map<String, String> headers) {
            this(status, body, headers, 0);
        }
    }

    record Seen(String pathAndQuery, Headers headers) {
    }

    /** GitHub falso: respostas em fila, guarda caminho+query e headers de cada pedido. */
    static final class FakeGitHub implements AutoCloseable {
        final ArrayDeque<Reply> replies = new ArrayDeque<>();
        final List<Seen> seen = new CopyOnWriteArrayList<>();
        final HttpServer server;

        FakeGitHub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", ex -> {
                String q = ex.getRequestURI().getRawQuery();
                seen.add(new Seen(ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q), ex.getRequestHeaders()));
                Reply r = replies.isEmpty() ? new Reply(500, "fila vazia", Map.of()) : replies.poll();
                if (r.delayMs() > 0) {
                    try {
                        Thread.sleep(r.delayMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
                byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
                if (b.length > 0) {
                    ex.getResponseBody().write(b);
                }
                ex.close();
            });
            server.start();
        }

        URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    FakeGitHub gh;
    Instant now = NOW;
    final Clock clock = new Clock() {
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    };

    @AfterEach
    void stop() {
        if (gh != null) {
            gh.close();
        }
    }

    GitHubFile file(String ref, Optional<String> token) throws Exception {
        gh = gh == null ? new FakeGitHub() : gh;
        return new GitHubFile(gh.base(), "ggfto/infra", "vps/services.yml", ref, token, Duration.ofSeconds(2), clock);
    }

    static UncheckedIOException fails(GitHubFile f) {
        return assertThrows(UncheckedIOException.class, f::get);
    }

    @Test
    void firstReadSendsRefRawAcceptAndBearerOnly() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(200, "zone: gf2.in\n", Map.of("ETag", "\"v1\"")));
        assertEquals("zone: gf2.in\n", f.get());
        Seen s = gh.seen.get(0);
        assertEquals("/repos/ggfto/infra/contents/vps/services.yml?ref=stable", s.pathAndQuery());
        assertEquals("Bearer " + TOKEN, s.headers().getFirst("Authorization"));
        assertEquals("application/vnd.github.raw+json", s.headers().getFirst("Accept"));
        assertEquals("2022-11-28", s.headers().getFirst("X-GitHub-Api-Version"));
        assertNull(s.headers().getFirst("If-None-Match"), "primeira leitura sem cache");
        assertFalse(s.pathAndQuery().contains(TOKEN), "token nunca na URL");
    }

    @Test
    void notModifiedReusesTheCacheAndChangedEtagBringsTheNewFile() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(200, "v: 1\n", Map.of("ETag", "\"v1\"")));
        gh.replies.add(new Reply(304, "", Map.of("ETag", "\"v1\"")));
        gh.replies.add(new Reply(200, "v: 2\n", Map.of("ETag", "\"v2\"")));
        gh.replies.add(new Reply(304, "", Map.of()));
        assertEquals("v: 1\n", f.get());
        assertEquals("v: 1\n", f.get());
        assertEquals("\"v1\"", gh.seen.get(1).headers().getFirst("If-None-Match"));
        assertEquals("v: 2\n", f.get());
        assertEquals("v: 2\n", f.get());
        assertEquals("\"v2\"", gh.seen.get(3).headers().getFirst("If-None-Match"));
    }

    @Test
    void privateRepoWithoutAccessIs404AndTheErrorSaysSoWithoutTheToken() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(404, "{\"message\":\"Not Found\"}", Map.of()));
        String msg = fails(f).getMessage();
        assertTrue(msg.contains("ggfto/infra") && msg.contains("vps/services.yml") && msg.contains("stable"), msg);
        assertTrue(msg.contains("repo privado"), msg);
        assertFalse(msg.contains(TOKEN), msg);
    }

    @Test
    void rejectedTokenIs401Error() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(401, "{\"message\":\"Bad credentials\"}", Map.of()));
        String msg = fails(f).getMessage();
        assertTrue(msg.contains("401"), msg);
        assertFalse(msg.contains(TOKEN), msg);
    }

    @Test
    void rateLimitStopsCallingUntilTheReset() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        Instant reset = NOW.plusSeconds(600);
        gh.replies.add(new Reply(403, "{\"message\":\"API rate limit exceeded\"}",
                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", String.valueOf(reset.getEpochSecond()))));
        String msg = fails(f).getMessage();
        assertTrue(msg.contains("rate limit") && msg.contains(reset.toString()), msg);
        now = NOW.plusSeconds(300);
        assertTrue(fails(f).getMessage().contains("rate limit"));
        assertEquals(1, gh.seen.size(), "antes do reset nao chama o GitHub de novo");
        now = reset.plusSeconds(1);
        gh.replies.add(new Reply(200, "ok\n", Map.of()));
        assertEquals("ok\n", f.get());
        assertEquals(2, gh.seen.size());
    }

    @Test
    void secondaryRateLimitUsesRetryAfter() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        // 120 s: diferente do padrao de 60 s do 429 sem cabecalho, para provar que o retry-after e lido
        gh.replies.add(new Reply(429, "{\"message\":\"secondary rate limit\"}", Map.of("retry-after", "120")));
        assertTrue(fails(f).getMessage().contains(NOW.plusSeconds(120).toString()));
        now = NOW.plusSeconds(90);
        fails(f);
        assertEquals(1, gh.seen.size());
    }

    @Test
    void forbiddenWithoutRateLimitIsAPermissionError() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(403, "{\"message\":\"Resource not accessible by personal access token\"}", Map.of("x-ratelimit-remaining", "4999")));
        String msg = fails(f).getMessage();
        assertTrue(msg.contains("403") && !msg.contains("rate limit"), msg);
        gh.replies.add(new Reply(200, "ok\n", Map.of()));
        assertEquals("ok\n", f.get(), "sem bloqueio: o proximo ciclo tenta de novo");
    }

    @Test
    void refIsEncodedAndPublicRepoWorksWithoutToken() throws Exception {
        GitHubFile f = file("feat/atlas x", Optional.empty());
        gh.replies.add(new Reply(200, "ok\n", Map.of()));
        f.get();
        assertEquals("/repos/ggfto/infra/contents/vps/services.yml?ref=feat%2Fatlas+x", gh.seen.get(0).pathAndQuery());
        assertNull(gh.seen.get(0).headers().getFirst("Authorization"));
    }

    @Test
    void redirectIsNotFollowed() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(301, "", Map.of("Location", "http://127.0.0.1:1/outro")));
        assertTrue(fails(f).getMessage().contains("301"));
        assertEquals(1, gh.seen.size());
    }

    @Test
    void timeoutIsAVisibleError() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(200, "tarde\n", Map.of(), 4000));
        assertTrue(fails(f).getMessage().contains("timeout"));
    }

    @Test
    void notModifiedWithoutCacheIsAnError() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        gh.replies.add(new Reply(304, "", Map.of()));
        assertTrue(fails(f).getMessage().contains("304"));
    }

    @Test
    void badCoordinatesAreRejectedUpFront() throws Exception {
        gh = new FakeGitHub();
        for (String[] bad : List.of(new String[] {"ggfto", "a.yml"}, new String[] {"ggfto/infra/x", "a.yml"},
                new String[] {"ggfto/infra", "../segredo"}, new String[] {"ggfto/infra", "/abs.yml"}, new String[] {"ggfto/infra", ""})) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GitHubFile(gh.base(), bad[0], bad[1], "stable", Optional.empty(), Duration.ofSeconds(1), clock));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new GitHubFile(gh.base(), "ggfto/infra", "a.yml", " ", Optional.empty(), Duration.ofSeconds(1), clock));
    }

    @Test
    void toStringNeverShowsTheToken() throws Exception {
        GitHubFile f = file("stable", Optional.of(TOKEN));
        assertFalse(f.toString().contains(TOKEN), f.toString());
        assertTrue(f.toString().contains("ggfto/infra"));
    }
}
