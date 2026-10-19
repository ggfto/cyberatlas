package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Status real (design §1.4; armadilha do Homelable: "< 500 = online" atras do Authentik). Rota protegida so esta
 * bem se redireciona para o host de login; 200 nela e alerta.
 */
class ExpectationTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");
    static final Expectation PROTECTED = new Expectation.Protected("auth.gf2.in");
    static final Expectation OPEN = new Expectation.Open("auth.gf2.in");

    static Observation run(Expectation e, String status, String location, String error) {
        ProbeResult r = error == null || error.isBlank() ? new ProbeResult(Integer.valueOf(status), blank(location), null)
                : new ProbeResult(null, null, error);
        return e.classify("route:x.gf2.in", r, AT, Duration.ofSeconds(90));
    }

    static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        "302 | https://auth.gf2.in/outpost.goauthentik.io/start?rd=x | - | ONLINE | protegida: redireciona ao login",
        "303 | https://AUTH.gf2.in/flow                                | - | ONLINE | protegida: redireciona ao login",
        "307 | https://auth.gf2.in/                                    | - | ONLINE | protegida: redireciona ao login",
        "200 | -                                                       | - | DEGRADED | rota protegida respondeu sem login (200)",
        "204 | -                                                       | - | DEGRADED | rota protegida respondeu sem login (204)",
        "302 | https://outro.gf2.in/                                   | - | DEGRADED | redireciona para fora do login (outro.gf2.in)",
        "302 | https://auth.gf2.in.evil.test/                          | - | DEGRADED | redireciona para fora do login (auth.gf2.in.evil.test)",
        "302 | /login                                                  | - | DEGRADED | redireciona para fora do login (relativo)",
        "302 | -                                                       | - | DEGRADED | redireciona para fora do login (sem Location)",
        "401 | -                                                       | - | DEGRADED | resposta inesperada (401)",
        "502 | -                                                       | - | OFFLINE | erro 502",
        "500 | -                                                       | - | OFFLINE | erro 500",
        "-   | -                                                       | timeout | OFFLINE | sem resposta: timeout",
    })
    void protectedRoute(String status, String location, String error, State state, String reason) {
        Observation o = run(PROTECTED, status, location, error);
        assertEquals(state, o.state());
        assertEquals(reason, o.reason());
        assertEquals("check:publico", o.source());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
        "200 | -                          | - | ONLINE | 200",
        "301 | https://ha.gf2.in/lovelace | - | ONLINE | redireciona (301)",
        "302 | https://auth.gf2.in/flow   | - | DEGRADED | rota sem auth pede login",
        "404 | -                          | - | DEGRADED | resposta inesperada (404)",
        "503 | -                          | - | OFFLINE | erro 503",
        "-   | -                          | conexao recusada | OFFLINE | sem resposta: conexao recusada",
    })
    void openRoute(String status, String location, String error, State state, String reason) {
        Observation o = run(OPEN, status, location, error);
        assertEquals(state, o.state());
        assertEquals(reason, o.reason());
    }

    @Test
    void edgeReportsTheSameRulesUnderItsOwnSource() {
        Observation o = PROTECTED.classify("route:fx.local.gf2.in", new ProbeResult(200, null, null), AT, Duration.ofSeconds(90),
                Expectation.LOCAL);
        assertEquals("check:local", o.source());
        assertEquals(State.DEGRADED, o.state());
    }
}
