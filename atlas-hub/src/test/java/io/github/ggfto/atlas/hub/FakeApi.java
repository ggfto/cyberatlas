package io.github.ggfto.atlas.hub;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** API HTTP falsa: resposta por caminho+query, registra os pedidos e o header Authorization recebido. */
public final class FakeApi implements AutoCloseable {

    public record Reply(int status, String body, long delayMs) {
        public Reply(int status, String body) {
            this(status, body, 0);
        }
    }

    final List<String> requested = new CopyOnWriteArrayList<>();
    final List<String> auth = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    public FakeApi(Map<String, Reply> replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery();
            String key = ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q);
            requested.add(key);
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            Reply r = replies.getOrDefault(key, new Reply(404, "{\"success\":false,\"errors\":[{\"code\":7003,\"message\":\"nao achou " + key + "\"}]}"));
            if (r.delayMs() > 0) {
                try {
                    Thread.sleep(r.delayMs());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
    }

    public URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
