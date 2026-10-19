package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.hub.AtlasConfig;
import io.github.ggfto.atlas.protocol.Csr;
import io.github.ggfto.atlas.protocol.Pem;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.UserData;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import java.lang.reflect.Proxy;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLSession;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Restart do hub: o edge enrollado antes tem que continuar entrando pelo EdgeUpgradeCheck. O "restart" e um EdgeHub
 * novo montado como no boot (mesma config, mesma CA, mesmo banco, nada em memoria) com um EdgeUpgradeCheck novo;
 * reiniciar o Quarkus inteiro no meio do teste nao da, e o estado que importa e so o do EdgeHub.
 */
@QuarkusTest
@TestProfile(EdgePkiProfile.class)
class EdgeRestartTest {

    @Inject
    AtlasConfig config;

    @Inject
    DataSource db;

    @Inject
    EdgeHub running;

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Map<String, Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (!answers.containsKey(m.getName())) {
                throw new UnsupportedOperationException(m.getName());
            }
            return answers.get(m.getName());
        });
    }

    static UserData userData(Map<Object, Object> data) {
        return (UserData) Proxy.newProxyInstance(UserData.class.getClassLoader(), new Class<?>[] {UserData.class}, (p, m, a) -> switch (m.getName()) {
            case "put" -> data.put(a[0], a[1]);
            case "get" -> data.get(a[0]);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    HttpUpgradeCheck.CheckResult handshake(EdgeHub hub, X509Certificate cert, Map<Object, Object> data) {
        SSLSession ssl = proxy(SSLSession.class, Map.of("getPeerCertificates", new Certificate[] {cert}));
        HttpServerRequest req = proxy(HttpServerRequest.class, Map.of("isSSL", true,
                "localAddress", SocketAddress.inetSocketAddress(EdgePkiProfile.EDGE_PORT, "127.0.0.1"), "sslSession", ssl));
        HttpUpgradeCheck.HttpUpgradeContext ctx = proxy(HttpUpgradeCheck.HttpUpgradeContext.class,
                Map.of("httpRequest", req, "userData", userData(data)));
        return new EdgeUpgradeCheck(hub).perform(ctx).await().atMost(Duration.ofSeconds(10));
    }

    EdgeHub restart() {
        return new EdgeHub(config, db);
    }

    static X509Certificate enroll(EnrollmentService svc, String token) throws Exception {
        return Pem.readCertificates(svc.enroll(token, Csr.pem(Csr.newKeyPair(), "x")).certificatePem()).get(0);
    }

    @Test
    void enrolledEdgeIsStillAcceptedAfterARestart() throws Exception {
        String m = EnrollmentServiceTest.machine();
        EnrollmentService live = running.enrollment().orElseThrow();
        X509Certificate cert = enroll(live, live.createEdge(m).token());

        Map<Object, Object> data = new HashMap<>();
        HttpUpgradeCheck.CheckResult r = handshake(restart(), cert, data);
        assertTrue(r.isUpgradePermitted(), "depois do restart o edge recebeu " + r.getHttpResponseCode());
        assertTrue(data.containsValue(m));
        assertTrue(data.containsValue("edge-" + m));
    }

    @Test
    void revokedAndReplacedCertificatesStayRefusedAfterARestart() throws Exception {
        String m = EnrollmentServiceTest.machine();
        EnrollmentService live = running.enrollment().orElseThrow();
        X509Certificate first = enroll(live, live.createEdge(m).token());
        X509Certificate second = enroll(live, live.reEnroll("edge-" + m).token());

        EdgeHub after = restart();
        assertEquals(403, handshake(after, first, new HashMap<>()).getHttpResponseCode(), "o cert anterior ao re-enroll");
        assertTrue(handshake(after, second, new HashMap<>()).isUpgradePermitted());

        live.revoke("edge-" + m);
        assertEquals(403, handshake(restart(), second, new HashMap<>()).getHttpResponseCode(), "edge revogado");
    }
}
