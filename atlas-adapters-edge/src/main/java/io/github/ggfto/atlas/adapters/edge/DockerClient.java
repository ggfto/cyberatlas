package io.github.ggfto.atlas.adapters.edge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Cliente HTTP minimo do Docker Engine, pelo socket Unix ou pelo TCP do docker-socket-proxy (design §2: o
 * docker-java nao vai bem em native).
 * So tres GETs passam, com allowlist aqui no codigo, alem do socket-proxy: a listagem, os eventos e os logs.
 * O inspect ({@code /containers/{id}/json}) traz {@code Config.Env} e nunca e chamado.
 */
public final class DockerClient {

    private static final String QUERY = "(\\?[A-Za-z0-9=&_%.-]*)?";
    private static final List<Pattern> ALLOWED = List.of(
            Pattern.compile("^/containers/json" + QUERY + "$"),
            Pattern.compile("^/events" + QUERY + "$"),
            Pattern.compile("^/containers/[a-f0-9]{12,64}/logs" + QUERY + "$"));

    private final java.net.SocketAddress address;
    private final Duration timeout;

    public DockerClient(Path socket, Duration timeout) {
        this(UnixDomainSocketAddress.of(socket), timeout);
    }

    private DockerClient(java.net.SocketAddress address, Duration timeout) {
        this.address = address;
        this.timeout = timeout;
    }

    /**
     * {@code unix:///var/run/docker.sock} ou {@code tcp://socket-proxy:2375} (o docker-socket-proxy usual expoe
     * TCP). Qualquer outro esquema e recusado.
     */
    public static DockerClient of(java.net.URI endpoint, Duration timeout) {
        return switch (String.valueOf(endpoint.getScheme())) {
            case "unix" -> new DockerClient(Path.of(endpoint.getPath()), timeout);
            case "tcp" -> new DockerClient(new java.net.InetSocketAddress(endpoint.getHost(), endpoint.getPort()), timeout);
            default -> throw new IllegalArgumentException("endpoint do docker so unix:// ou tcp://: " + endpoint);
        };
    }

    public static boolean allowed(String path) {
        return ALLOWED.stream().anyMatch(p -> p.matcher(path).matches());
    }

    public String get(String path) throws IOException {
        if (!allowed(path)) {
            throw new IllegalArgumentException("endpoint do docker fora da allowlist: " + path);
        }
        SocketChannel ch = address instanceof UnixDomainSocketAddress
                ? SocketChannel.open(StandardProtocolFamily.UNIX) : SocketChannel.open();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = pool.submit(() -> exchange(ch, path));
            try {
                return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                ch.close();
                throw new IOException("docker GET " + path + ": timeout");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("docker GET " + path + ": interrompido");
            }
        } finally {
            ch.close();
        }
    }

    private String exchange(SocketChannel ch, String path) throws IOException {
        ch.connect(address);
        String req = "GET " + path + " HTTP/1.1\r\nHost: docker\r\nUser-Agent: cyberatlas-edge\r\nConnection: close\r\n\r\n";
        ch.write(ByteBuffer.wrap(req.getBytes(StandardCharsets.US_ASCII)));
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(16 * 1024);
        while (ch.read(buf) >= 0) {
            buf.flip();
            all.write(buf.array(), 0, buf.limit());
            buf.clear();
        }
        return parse(path, all.toByteArray());
    }

    /**
     * GET em streaming (logs com follow): conecta e le o cabecalho aqui (erro sobe para quem chamou); o corpo
     * chega em pedacos numa virtual thread ate o fim ou ate o {@link AutoCloseable} ser fechado.
     */
    public AutoCloseable stream(String path, java.util.function.Consumer<byte[]> body, Runnable onEnd) throws IOException {
        if (!allowed(path)) {
            throw new IllegalArgumentException("endpoint do docker fora da allowlist: " + path);
        }
        SocketChannel ch = address instanceof UnixDomainSocketAddress
                ? SocketChannel.open(StandardProtocolFamily.UNIX) : SocketChannel.open();
        byte[] rest;
        boolean chunked;
        try {
            ch.connect(address);
            String req = "GET " + path + " HTTP/1.1\r\nHost: docker\r\nUser-Agent: cyberatlas-edge\r\nConnection: close\r\n\r\n";
            ch.write(ByteBuffer.wrap(req.getBytes(StandardCharsets.US_ASCII)));
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int split;
            byte[] sep = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while ((split = indexOf(head.toByteArray(), sep, 0)) < 0) {
                if (ch.read(buf) < 0) {
                    throw new IOException("docker GET " + path + ": conexao fechada antes do cabecalho");
                }
                buf.flip();
                head.write(buf.array(), 0, buf.limit());
                buf.clear();
            }
            byte[] all = head.toByteArray();
            String[] lines = new String(all, 0, split, StandardCharsets.US_ASCII).split("\r\n");
            int code = Integer.parseInt(lines[0].split(" ", 3)[1]);
            if (code != 200) {
                throw new IOException("docker GET " + path + ": HTTP " + code);
            }
            chunked = java.util.Arrays.stream(lines).anyMatch(h -> h.toLowerCase(Locale.ROOT).startsWith("transfer-encoding:")
                    && h.toLowerCase(Locale.ROOT).contains("chunked"));
            rest = java.util.Arrays.copyOfRange(all, split + 4, all.length);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        Thread.ofVirtual().name("docker-logs").start(() -> {
            ChunkDecoder dec = new ChunkDecoder(chunked, body);
            try {
                dec.feed(rest);
                ByteBuffer b = ByteBuffer.allocate(16 * 1024);
                while (!dec.done() && ch.read(b) >= 0) {
                    b.flip();
                    dec.feed(java.util.Arrays.copyOf(b.array(), b.limit()));
                    b.clear();
                }
            } catch (IOException e) {
                // fechado por nos (close) ou pela outra ponta: fim do stream
            } finally {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // ja fechado
                }
                if (!closed.get()) {
                    onEnd.run();
                }
            }
        });
        return () -> {
            closed.set(true);
            ch.close();
        };
    }

    /** Transfer-Encoding: chunked incremental (ou repasse direto sem chunked). */
    private static final class ChunkDecoder {
        private final boolean chunked;
        private final java.util.function.Consumer<byte[]> out;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private int left = -1;
        private boolean done;

        ChunkDecoder(boolean chunked, java.util.function.Consumer<byte[]> out) {
            this.chunked = chunked;
            this.out = out;
        }

        boolean done() {
            return done;
        }

        void feed(byte[] data) {
            if (!chunked) {
                if (data.length > 0) {
                    out.accept(data);
                }
                return;
            }
            int i = 0;
            while (i < data.length && !done) {
                if (left < 0) {
                    byte c = data[i++];
                    if (c == '\n') {
                        String size = line.toString(StandardCharsets.US_ASCII).trim();
                        line.reset();
                        if (size.isEmpty()) {
                            continue;
                        }
                        left = Integer.parseInt(size.split(";")[0], 16);
                        if (left == 0) {
                            done = true;
                        }
                    } else {
                        line.write(c);
                    }
                } else {
                    int n = Math.min(left, data.length - i);
                    out.accept(java.util.Arrays.copyOfRange(data, i, i + n));
                    i += n;
                    left -= n;
                    if (left == 0) {
                        left = -1;
                    }
                }
            }
        }
    }

    static String parse(String path, byte[] raw) throws IOException {
        int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
        if (split < 0) {
            throw new IOException("docker GET " + path + ": resposta sem cabecalho");
        }
        String[] head = new String(raw, 0, split, StandardCharsets.US_ASCII).split("\r\n");
        int code = Integer.parseInt(head[0].split(" ", 3)[1]);
        boolean chunked = false;
        for (int i = 1; i < head.length; i++) {
            String h = head[i].toLowerCase(Locale.ROOT);
            if (h.startsWith("transfer-encoding:") && h.contains("chunked")) {
                chunked = true;
            }
        }
        byte[] body = java.util.Arrays.copyOfRange(raw, split + 4, raw.length);
        if (code != 200) {
            throw new IOException("docker GET " + path + ": HTTP " + code);
        }
        return new String(chunked ? dechunk(body) : body, StandardCharsets.UTF_8);
    }

    /** Transfer-Encoding: chunked em bytes (o tamanho do chunk e em bytes, nao em caracteres). */
    private static byte[] dechunk(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
        int i = 0;
        while (i < body.length) {
            int eol = indexOf(body, crlf, i);
            if (eol < 0) {
                throw new IOException("chunk sem tamanho");
            }
            int size = Integer.parseInt(new String(body, i, eol - i, StandardCharsets.US_ASCII).split(";")[0].trim(), 16);
            if (size == 0) {
                break;
            }
            out.write(body, eol + 2, size);
            i = eol + 2 + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = from; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
