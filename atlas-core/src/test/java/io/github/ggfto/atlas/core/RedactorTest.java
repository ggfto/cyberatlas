package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Redacao de log (defesa extra, design §4). Valores sinteticos com o formato dos tokens reais. */
class RedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "push com ghp_0123456789abcdefghijABCDEFGHIJ012345 ok | push com [redacted:github] ok",
        "pat github_pat_11ABCDEFG0123456789_abcdefghijklmnop fim | pat [redacted:github] fim",
        "chave sk-proj-0123456789abcdefABCDEF usada | chave [redacted:apikey] usada",
        "Authorization: Bearer abc.def-ghi_jkl==xyz | Authorization: [redacted:bearer]",
        "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk ok | jwt [redacted:jwt] ok",
        "aws AKIAIOSFODNN7EXAMPLE x | aws [redacted:aws] x",
        "-----BEGIN RSA PRIVATE KEY----- | [redacted:pem]",
    })
    void redactsKnownSecretShapes(String in, String out) {
        assertEquals(out, Redactor.DEFAULT.redact(in));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "GET /login?password=hunter2&user=a | GET /login?password=[redacted:kv]&user=a",
        "{\"token\": \"abc123\", \"n\": 1} | {\"token\": \"[redacted:kv]\", \"n\": 1}",
        "API_KEY=xyz app iniciada | API_KEY=[redacted:kv] app iniciada",
        "secret: s3cr3t | secret: [redacted:kv]",
    })
    void keyValueKeepsTheKeyAndHidesTheValue(String in, String out) {
        assertEquals(out, Redactor.DEFAULT.redact(in));
    }

    @Test
    void ordinaryLinesAreUntouched() {
        String line = "2026-10-19 INFO servidor ouvindo em :8080 (token de sessao renovado)";
        assertEquals(line, Redactor.DEFAULT.redact(line));
    }

    @Test
    void pathologicalInputStaysLinear() {
        // backtracking explodiria aqui; RE2J termina em tempo linear
        String evil = "a".repeat(200_000) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> Redactor.DEFAULT.redact(evil));
    }
}
