package io.github.ggfto.atlas.adapters.edge;

import io.github.ggfto.atlas.core.LogSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;

/**
 * Le o corpo de {@code /containers/{id}/logs?timestamps=1} em pedacos arbitrarios. Sem TTY o Docker multiplexa:
 * cada bloco tem 8 bytes de cabecalho (tipo 1=stdout 2=stderr, tres zeros, tamanho big-endian). Com TTY vem cru.
 * As linhas sao montadas em bytes e so decodificadas inteiras (caractere multibyte cortado no meio nao quebra).
 */
public final class DockerLogDemux {

    private enum Mode { UNKNOWN, MUX, RAW }

    private final Consumer<LogSource.RawLine> out;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private Mode mode = Mode.UNKNOWN;
    private int frameType;
    private int frameLeft;

    public DockerLogDemux(Consumer<LogSource.RawLine> out) {
        this.out = out;
    }

    public void accept(byte[] chunk) {
        pending.writeBytes(chunk);
        byte[] buf = pending.toByteArray();
        pending.reset();
        int i = 0;
        if (mode == Mode.UNKNOWN) {
            if (buf.length < 8) {
                pending.writeBytes(buf);
                return;
            }
            mode = (buf[0] == 0 || buf[0] == 1 || buf[0] == 2) && buf[1] == 0 && buf[2] == 0 && buf[3] == 0 ? Mode.MUX : Mode.RAW;
        }
        if (mode == Mode.RAW) {
            lines(stdout, buf, 0, buf.length, "stdout");
            return;
        }
        while (i < buf.length) {
            if (frameLeft == 0) {
                if (buf.length - i < 8) {
                    pending.write(buf, i, buf.length - i);
                    return;
                }
                frameType = buf[i];
                frameLeft = ((buf[i + 4] & 0xff) << 24) | ((buf[i + 5] & 0xff) << 16) | ((buf[i + 6] & 0xff) << 8) | (buf[i + 7] & 0xff);
                i += 8;
                continue;
            }
            int n = Math.min(frameLeft, buf.length - i);
            if (frameType == 2) {
                lines(stderr, buf, i, n, "stderr");
            } else {
                lines(stdout, buf, i, n, "stdout");
            }
            frameLeft -= n;
            i += n;
        }
    }

    /** Fim do corpo: entrega o que sobrou sem quebra de linha. */
    public void finish() {
        if (mode == Mode.UNKNOWN && pending.size() > 0) {
            mode = Mode.RAW;
            byte[] rest = pending.toByteArray();
            pending.reset();
            lines(stdout, rest, 0, rest.length, "stdout");
        }
        flush(stdout, "stdout");
        flush(stderr, "stderr");
    }

    private void lines(ByteArrayOutputStream acc, byte[] buf, int from, int len, String stream) {
        for (int j = from; j < from + len; j++) {
            if (buf[j] == '\n') {
                emit(acc.toByteArray(), stream);
                acc.reset();
            } else {
                acc.write(buf[j]);
            }
        }
    }

    private void flush(ByteArrayOutputStream acc, String stream) {
        if (acc.size() > 0) {
            emit(acc.toByteArray(), stream);
            acc.reset();
        }
    }

    private void emit(byte[] raw, String stream) {
        out.accept(parse(new String(raw, StandardCharsets.UTF_8), stream));
    }

    /** {@code 2026-10-19T12:00:00.123456789Z texto} (timestamps=1); sem timestamp, a hora da leitura. */
    static LogSource.RawLine parse(String line, String stream) {
        String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        int sp = l.indexOf(' ');
        if (sp > 0 && sp <= 35 && l.charAt(sp - 1) == 'Z') {
            try {
                return new LogSource.RawLine(Instant.parse(l.substring(0, sp)), stream, l.substring(sp + 1));
            } catch (DateTimeParseException ignored) {
                // nao era timestamp
            }
        }
        return new LogSource.RawLine(Instant.now(), stream, l);
    }
}
