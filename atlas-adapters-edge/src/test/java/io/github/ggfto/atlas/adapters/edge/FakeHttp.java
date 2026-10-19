package io.github.ggfto.atlas.adapters.edge;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** API HTTP falsa que registra cada caminho; caminho fora do mapa responde 500 e fica registrado. */
final class FakeHttp implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers) {
        Reply(int status, String body) {
            this(status, body, Map.of());
        }
    }

    final List<String> requested = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    FakeHttp(Map<String, Reply> replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath();
            requested.add(path);
            Reply r = replies.getOrDefault(path, new Reply(500, "{}"));
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
