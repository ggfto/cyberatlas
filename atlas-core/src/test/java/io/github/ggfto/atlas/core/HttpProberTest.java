package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpProberTest {

    HttpServer server;
    String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/protegida", ex -> {
            ex.getResponseHeaders().add("Location", "https://auth.gf2.in/flow");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/lenta", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void doesNotFollowTheRedirect() {
        ProbeResult r = new HttpProber(Duration.ofSeconds(2)).probe(URI.create(base + "/protegida"));
        assertEquals(302, r.status());
        assertEquals("https://auth.gf2.in/flow", r.location());
        assertNull(r.error());
    }

    @Test
    void slowAnswerIsATimeout() {
        ProbeResult r = new HttpProber(Duration.ofMillis(300)).probe(URI.create(base + "/lenta"));
        assertEquals("timeout", r.error());
        assertNull(r.status());
    }

    @Test
    void closedPortIsConnectionRefused() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertEquals("conexao recusada", new HttpProber(Duration.ofSeconds(2)).probe(URI.create("http://127.0.0.1:" + port + "/")).error());
    }
}
