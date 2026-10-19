package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.protocol.Csr;
import io.github.ggfto.atlas.protocol.Pem;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Enrollment (molde do HiveKeeper): token de uso unico, 1 h, guardado so como hash; revogacao; cert vigente. */
class EnrollmentServiceTest {

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-19T12:00:00Z"));
    final EnrollmentService svc = new EnrollmentService(AtlasCa.generate("ca", Duration.ofDays(30)), now::get);

    String csr() throws Exception {
        return Csr.pem(Csr.newKeyPair(), "qualquer");
    }

    @Test
    void tokenEnrollsOnceAndOnlyOnce() throws Exception {
        EnrollmentService.NewEdge e = svc.createEdge("vps");
        EnrollmentService.Issued issued = svc.enroll(e.token(), csr());
        X509Certificate cert = Pem.readCertificates(issued.certificatePem()).get(0);
        assertEquals("CN=" + e.edgeId(), cert.getSubjectX500Principal().getName());
        assertEquals("edge-vps", e.edgeId());
        EnrollmentException again = assertThrows(EnrollmentException.class, () -> svc.enroll(e.token(), csr()));
        assertEquals("token de enrollment invalido, vencido ou ja usado", again.getMessage());
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        EnrollmentService.NewEdge e = svc.createEdge("host");
        now.set(now.get().plus(Duration.ofMinutes(61)));
        assertThrows(EnrollmentException.class, () -> svc.enroll(e.token(), csr()));
    }

    @Test
    void unknownTokenIsRefusedTheSameWay() {
        EnrollmentException e = assertThrows(EnrollmentException.class, () -> svc.enroll("inventado", csr()));
        assertEquals("token de enrollment invalido, vencido ou ja usado", e.getMessage());
    }

    @Test
    void tokenIsNeverKeptInPlainText() throws Exception {
        EnrollmentService.NewEdge e = svc.createEdge("vps");
        assertFalse(svc.toString().contains(e.token()));
        assertTrue(svc.storedTokenHashes().stream().noneMatch(h -> h.contains(e.token())));
    }

    @Test
    void onlyTheCurrentCertificateOfAnActiveEdgeIsAccepted() throws Exception {
        EnrollmentService.NewEdge e = svc.createEdge("vps");
        X509Certificate first = Pem.readCertificates(svc.enroll(e.token(), csr()).certificatePem()).get(0);
        assertEquals(java.util.Optional.of("vps"), svc.activeMachine(first));
        EnrollmentService.NewEdge again = svc.reEnroll("edge-vps");
        X509Certificate second = Pem.readCertificates(svc.enroll(again.token(), csr()).certificatePem()).get(0);
        assertEquals(java.util.Optional.empty(), svc.activeMachine(first), "o cert anterior deixa de valer");
        assertEquals(java.util.Optional.of("vps"), svc.activeMachine(second));
        svc.revoke("edge-vps");
        assertEquals(java.util.Optional.empty(), svc.activeMachine(second));
        assertThrows(EnrollmentException.class, () -> svc.reEnroll("edge-nao-existe"));
    }

    @Test
    void oneEdgePerMachine() {
        svc.createEdge("vps");
        EnrollmentException e = assertThrows(EnrollmentException.class, () -> svc.createEdge("vps"));
        assertEquals("ja existe edge para a maquina vps (use re-enroll)", e.getMessage());
        assertThrows(EnrollmentException.class, () -> svc.createEdge("../vps"));
    }
}
