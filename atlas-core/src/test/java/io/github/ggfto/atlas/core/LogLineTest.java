package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Linha de log como sai do edge e do hub (design §2.4): cortada em 16 KiB e sempre ja redigida. */
class LogLineTest {

    static final Instant AT = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void longLineIsCutAndMarked() {
        LogLine l = LogLine.of(AT, "stdout", "x".repeat(LogLine.MAX_CHARS + 10), Redactor.DEFAULT);
        assertEquals(LogLine.MAX_CHARS, l.text().length());
        assertTrue(l.truncated());
    }

    @Test
    void secretsAreRedactedBeforeTheCut() {
        LogLine l = LogLine.of(AT, "stdout", "token=abc123 e um ghp_0123456789abcdefghijABCDEFGHIJ012345", Redactor.DEFAULT);
        assertEquals("token=[redacted:kv] e um [redacted:github]", l.text());
        assertFalse(l.truncated());
    }

    @Test
    void streamIsStdoutOrStderr() {
        assertEquals("stdout", LogLine.of(AT, "qualquer", "x", Redactor.DEFAULT).stream());
        assertEquals("stderr", LogLine.of(AT, "stderr", "x", Redactor.DEFAULT).stream());
    }
}
