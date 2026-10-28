package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.jwt.build.Jwt;
import java.net.URI;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Issue #20: o SSE do mapa nao sobrevive ao token que o abriu. Quando ele vence, o hub manda
 * {@code end {reason:"auth"}} e fecha; o front confere a sessao (o hub renova pelo refresh, ou da 401 e o /embed
 * mostra "Entrar no atlas").
 */
@QuarkusTest
@TestProfile(FixtureProfile.class)
class TopologyStreamExpiryTest {

    @TestHTTPResource("/api/topology/stream")
    URI stream;

    @Test
    void streamEndsWithReasonAuthWhenTheTokenExpires() throws Exception {
        // folga para a maquina carregada: o token tem de estar valido quando o hub o validar
        long exp = Instant.now().getEpochSecond() + 15;
        BlockingQueue<TopologyStreamTest.Event> events = TopologyStreamTest.open(stream, null, viewerExpiringAt(exp));

        TopologyStreamTest.Event first = events.poll(30, TimeUnit.SECONDS);
        assertNotNull(first, "sem snapshot");
        assertEquals("snapshot", first.name());

        TopologyStreamTest.Event end = poll(events, "end", 60);
        assertNotNull(end, "o stream nao fechou com end depois do exp do token");
        assertEquals(Map.of("reason", "auth"), new ObjectMapper().readValue(end.data(), Map.class));
        assertTrue(Instant.now().getEpochSecond() >= exp, "end antes do token vencer");
    }

    @Test
    void streamWithALongLivedTokenStaysOpen() throws Exception {
        BlockingQueue<TopologyStreamTest.Event> events = TopologyStreamTest.open(stream, null);
        assertEquals("snapshot", events.poll(30, TimeUnit.SECONDS).name());
        assertNull(poll(events, "end", 5));
    }

    private static TopologyStreamTest.Event poll(BlockingQueue<TopologyStreamTest.Event> events, String name, int seconds)
            throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        for (long left; (left = until - System.nanoTime()) > 0; ) {
            TopologyStreamTest.Event e = events.poll(left, TimeUnit.NANOSECONDS);
            if (e != null && name.equals(e.name())) {
                return e;
            }
        }
        return null;
    }

    /** O token de viewer do Authentik de mentira, com outro exp (mesma chave, issuer e audience). */
    @SuppressWarnings("unchecked")
    static String viewerExpiringAt(long exp) throws Exception {
        String payload = Tokens.viewer().split("\\.")[1];
        Map<String, Object> claims = new ObjectMapper().readValue(Base64.getUrlDecoder().decode(payload), Map.class);
        claims.put("exp", exp);
        return Jwt.claims(claims).jws().keyId("1").sign("privateKey.jwk");
    }
}
