package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.github.ggfto.atlas.protocol.Csr;
import io.github.ggfto.atlas.protocol.Pem;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.security.cert.X509Certificate;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Enrollment (molde do HiveKeeper): token de uso unico, 1 h, guardado so como hash; revogacao; cert vigente. Tudo no
 * Postgres do devservices: cada teste usa maquinas proprias (o banco e compartilhado com os outros testes).
 */
@QuarkusTest
@TestProfile(FixtureProfile.class)
class EnrollmentServiceTest {

    static final AtlasCa CA = AtlasCa.generate("ca", Duration.ofDays(30));

    @Inject
    DataSource db;

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    EnrollmentService svc() {
        return new EnrollmentService(CA, db, now::get);
    }

    static String machine() {
        return "t-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    static String csr() throws Exception {
        return Csr.pem(Csr.newKeyPair(), "qualquer");
    }

    static X509Certificate cert(EnrollmentService.Issued i) throws Exception {
        return Pem.readCertificates(i.certificatePem()).get(0);
    }

    @Test
    void tokenEnrollsOnceAndOnlyOnce() throws Exception {
        EnrollmentService svc = svc();
        String m = machine();
        EnrollmentService.NewEdge e = svc.createEdge(m);
        EnrollmentService.Issued issued = svc.enroll(e.token(), csr());
        assertEquals("CN=" + e.edgeId(), cert(issued).getSubjectX500Principal().getName());
        assertEquals("edge-" + m, e.edgeId());
        EnrollmentException again = assertThrows(EnrollmentException.class, () -> svc.enroll(e.token(), csr()));
        assertEquals("token de enrollment invalido, vencido ou ja usado", again.getMessage());
    }

    @Test
    void enrolledEdgeSurvivesARestartOfTheHub() throws Exception {
        String m = machine();
        EnrollmentService before = svc();
        X509Certificate c = cert(before.enroll(before.createEdge(m).token(), csr()));
        // restart: instancia nova, nada em memoria, so o mesmo banco
        EnrollmentService after = svc();
        assertEquals(Optional.of(m), after.activeMachine(c));
        EnrollmentException dup = assertThrows(EnrollmentException.class, () -> after.createEdge(m));
        assertEquals("ja existe edge para a maquina " + m + " (use re-enroll)", dup.getMessage());
    }

    @Test
    void pendingTokenSurvivesARestartAndIsStillSingleUse() throws Exception {
        EnrollmentService.NewEdge e = svc().createEdge(machine());
        EnrollmentService after = svc();
        after.enroll(e.token(), csr());
        assertThrows(EnrollmentException.class, () -> svc().enroll(e.token(), csr()));
    }

    @Test
    void twoParallelEnrollmentsWithTheSameTokenExactlyOneWins() throws Exception {
        String token = svc().createEdge(machine()).token();
        int n = 8;
        // duas "replicas" do hub no mesmo banco, todas as threads largam juntas
        List<EnrollmentService> replicas = List.of(svc(), svc());
        CyclicBarrier start = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                EnrollmentService s = replicas.get(i % 2);
                String csr = csr();
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        s.enroll(token, csr);
                        return true;
                    } catch (EnrollmentException ex) {
                        assertEquals("token de enrollment invalido, vencido ou ja usado", ex.getMessage());
                        return false;
                    }
                }));
            }
            int wins = 0;
            for (Future<Boolean> f : results) {
                wins += f.get() ? 1 : 0;
            }
            assertEquals(1, wins, "um token, um enrollment");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredTokenIsRefusedAndPurged() throws Exception {
        EnrollmentService svc = svc();
        EnrollmentService.NewEdge e = svc.createEdge(machine());
        now.set(now.get().plus(Duration.ofMinutes(61)));
        EnrollmentException ex = assertThrows(EnrollmentException.class, () -> svc.enroll(e.token(), csr()));
        assertEquals("token de enrollment invalido, vencido ou ja usado", ex.getMessage());
        assertTrue(svc.purgeExpiredTokens() >= 1);
        try (var c = db.getConnection(); var ps = c.prepareStatement("select count(*) from enrollment_token where edge_id = ?")) {
            ps.setString(1, e.edgeId());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void unknownTokenIsRefusedTheSameWay() {
        EnrollmentException e = assertThrows(EnrollmentException.class, () -> svc().enroll("inventado", csr()));
        assertEquals("token de enrollment invalido, vencido ou ja usado", e.getMessage());
    }

    @Test
    void tokenIsNeverKeptInPlainText() throws Exception {
        EnrollmentService svc = svc();
        EnrollmentService.NewEdge e = svc.createEdge(machine());
        assertFalse(svc.toString().contains(e.token()));
        List<String> hashes = svc.storedTokenHashes();
        assertFalse(hashes.isEmpty());
        assertTrue(hashes.stream().noneMatch(h -> h.contains(e.token())));
        try (var c = db.getConnection(); var ps = c.prepareStatement("select * from enrollment_token where edge_id = ?")) {
            ps.setString(1, e.edgeId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    assertFalse(String.valueOf(rs.getObject(i)).contains(e.token()));
                }
            }
        }
    }

    @Test
    void onlyTheCurrentCertificateOfAnActiveEdgeIsAccepted() throws Exception {
        EnrollmentService svc = svc();
        String m = machine();
        EnrollmentService.NewEdge e = svc.createEdge(m);
        X509Certificate first = cert(svc.enroll(e.token(), csr()));
        assertEquals(Optional.of(m), svc.activeMachine(first));
        EnrollmentService.NewEdge again = svc.reEnroll(e.edgeId());
        assertEquals(Optional.of(m), svc().activeMachine(first), "o cert atual vale ate o novo enrollment");
        X509Certificate second = cert(svc.enroll(again.token(), csr()));
        assertEquals(Optional.empty(), svc().activeMachine(first), "o cert anterior deixa de valer");
        assertEquals(Optional.of(m), svc().activeMachine(second));
        svc.revoke(e.edgeId());
        assertEquals(Optional.empty(), svc().activeMachine(second));
        assertThrows(EnrollmentException.class, () -> svc.reEnroll("edge-nao-existe"));
    }

    @Test
    void reEnrollReplacesThePendingToken() throws Exception {
        EnrollmentService svc = svc();
        EnrollmentService.NewEdge e = svc.createEdge(machine());
        EnrollmentService.NewEdge again = svc.reEnroll(e.edgeId());
        assertThrows(EnrollmentException.class, () -> svc.enroll(e.token(), csr()));
        svc.enroll(again.token(), csr());
    }

    @Test
    void oneEdgePerMachine() {
        EnrollmentService svc = svc();
        String m = machine();
        svc.createEdge(m);
        EnrollmentException e = assertThrows(EnrollmentException.class, () -> svc.createEdge(m));
        assertEquals("ja existe edge para a maquina " + m + " (use re-enroll)", e.getMessage());
        assertEquals("nome de maquina invalido", assertThrows(EnrollmentException.class, () -> svc.createEdge("../vps")).getMessage());
    }
}
