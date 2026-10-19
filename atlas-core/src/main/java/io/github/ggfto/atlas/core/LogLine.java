package io.github.ggfto.atlas.core;

import java.time.Instant;

/**
 * Uma linha de log como trafega (design §2.4): {@code stdout}/{@code stderr}, ja redigida e cortada em
 * {@link #MAX_CHARS}. Use {@link #of} para nunca montar uma linha que pulou a redacao.
 */
public record LogLine(Instant ts, String stream, String text, boolean truncated) {

    public static final int MAX_CHARS = 16 * 1024;

    public static LogLine of(Instant ts, String stream, String raw, Redactor redactor) {
        String clean = redactor.redact(raw == null ? "" : raw);
        boolean cut = clean.length() > MAX_CHARS;
        return new LogLine(ts, "stderr".equals(stream) ? "stderr" : "stdout", cut ? clean.substring(0, MAX_CHARS) : clean, cut);
    }
}
