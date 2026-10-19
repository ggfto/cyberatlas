package io.github.ggfto.atlas.adapters.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ggfto.atlas.core.LogSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Formato do /containers/{id}/logs sem TTY: blocos com cabecalho de 8 bytes (tipo, 3 zeros, tamanho big-endian). */
class DockerLogDemuxTest {

    static byte[] frame(int type, String payload) {
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        out.write(0);
        out.write(0);
        out.write(0);
        out.write((p.length >>> 24) & 0xff);
        out.write((p.length >>> 16) & 0xff);
        out.write((p.length >>> 8) & 0xff);
        out.write(p.length & 0xff);
        out.writeBytes(p);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    @Test
    void multiplexedFramesSplitAnywhereBecomeLinesWithStreamAndTimestamp() {
        byte[] all = concat(frame(1, "2026-10-19T12:00:00.123456789Z servidor ouvindo\n"),
                frame(2, "2026-10-19T12:00:01.000000000Z ERRO: falhou\n2026-10-19T12:00:02Z sem"), frame(2, " quebra ação\n"));
        List<LogSource.RawLine> lines = new ArrayList<>();
        DockerLogDemux d = new DockerLogDemux(lines::add);
        // pedacos de 5 bytes: cabecalho e caracteres multibyte cortados no meio
        for (int i = 0; i < all.length; i += 5) {
            d.accept(java.util.Arrays.copyOfRange(all, i, Math.min(all.length, i + 5)));
        }
        d.finish();
        assertEquals(List.of(
                new LogSource.RawLine(java.time.Instant.parse("2026-10-19T12:00:00.123456789Z"), "stdout", "servidor ouvindo"),
                new LogSource.RawLine(java.time.Instant.parse("2026-10-19T12:00:01Z"), "stderr", "ERRO: falhou"),
                new LogSource.RawLine(java.time.Instant.parse("2026-10-19T12:00:02Z"), "stderr", "sem quebra ação")), lines);
    }

    @Test
    void ttyStreamWithoutHeadersIsReadRaw() {
        List<LogSource.RawLine> lines = new ArrayList<>();
        DockerLogDemux d = new DockerLogDemux(lines::add);
        d.accept("2026-10-19T12:00:00Z tty sem cabecalho\nlinha sem timestamp\n".getBytes(StandardCharsets.UTF_8));
        d.finish();
        assertEquals("tty sem cabecalho", lines.get(0).text());
        assertEquals("linha sem timestamp", lines.get(1).text());
        assertEquals("stdout", lines.get(1).stream());
    }

    @Test
    void lastLineWithoutNewlineIsDeliveredAtTheEnd() {
        List<LogSource.RawLine> lines = new ArrayList<>();
        DockerLogDemux d = new DockerLogDemux(lines::add);
        d.accept(frame(1, "2026-10-19T12:00:00Z sem enter"));
        assertEquals(0, lines.size());
        d.finish();
        assertEquals("sem enter", lines.get(0).text());
    }
}
