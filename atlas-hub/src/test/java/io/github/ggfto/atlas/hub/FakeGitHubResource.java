package io.github.ggfto.atlas.hub;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * GitHub de mentira para o hub: serve o services.yml sanitizado da VPS (fixture) em contents?ref=stable, com ETag,
 * ou responde 404 (repo privado sem acesso) com o init arg {@code mode=404}. Guarda o Authorization recebido.
 */
public class FakeGitHubResource implements QuarkusTestResourceLifecycleManager {

    public static final String TOKEN = "github_pat_HUB-SO-DE-TESTE";
    public static final List<String> AUTH = new CopyOnWriteArrayList<>();
    public static final List<String> PATHS = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String mode = "ok";

    @Override
    public void init(Map<String, String> args) {
        mode = args.getOrDefault("mode", "ok");
    }

    @Override
    public Map<String, String> start() {
        try {
            byte[] yml = Files.readAllBytes(Path.of(FixtureProfile.FIXTURES + "services-vps.yml"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", ex -> {
                String q = ex.getRequestURI().getRawQuery();
                PATHS.add(ex.getRequestURI().getRawPath() + (q == null ? "" : "?" + q));
                AUTH.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                boolean ours = "/repos/ggfto/infra/contents/vps/services.yml".equals(ex.getRequestURI().getRawPath()) && "ref=stable".equals(q);
                if ("404".equals(mode) || !ours) {
                    byte[] b = "{\"message\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(404, b.length);
                    ex.getResponseBody().write(b);
                } else if ("\"vps-1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                    ex.sendResponseHeaders(304, -1);
                } else {
                    ex.getResponseHeaders().add("ETag", "\"vps-1\"");
                    ex.sendResponseHeaders(200, yml.length);
                    ex.getResponseBody().write(yml);
                }
                ex.close();
            });
            server.start();
            return Map.of(
                    "atlas.servicesyml.github.api", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "atlas.servicesyml.github.repo", "ggfto/infra",
                    "atlas.servicesyml.github.token", TOKEN,
                    "atlas.servicesyml.github.paths.vps", "vps/services.yml",
                    "atlas.servicesyml.files.host", FixtureProfile.FIXTURES + "services-host.yml",
                    "atlas.checks.enabled", "false");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
