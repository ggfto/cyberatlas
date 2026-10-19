package io.github.ggfto.atlas.hub.edge;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Handshake do WebSocket dos edges: porta de edge em TLS, certificado de cliente apresentado e ele e o vigente de
 * um edge nao revogado. A identidade (edgeId/maquina) vem do certificado e segue no userData da conexao.
 */
@ApplicationScoped
public class EdgeUpgradeCheck implements HttpUpgradeCheck {

    private final EdgeHub hub;

    EdgeUpgradeCheck(EdgeHub hub) {
        this.hub = hub;
    }

    @Override
    public boolean appliesTo(String endpointId) {
        return EdgeSocket.ENDPOINT.equals(endpointId);
    }

    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext ctx) {
        HttpServerRequest req = ctx.httpRequest();
        if (!req.isSSL() || req.localAddress().port() != hub.config().port()) {
            return CheckResult.rejectUpgrade(403);
        }
        X509Certificate cert;
        try {
            Certificate[] chain = req.sslSession().getPeerCertificates();
            cert = (X509Certificate) chain[0];
        } catch (SSLPeerUnverifiedException | NullPointerException e) {
            return CheckResult.rejectUpgrade(401);
        }
        if (hub.enrollment().isEmpty()) {
            return CheckResult.rejectUpgrade(503);
        }
        EnrollmentService enrollment = hub.enrollment().get();
        // consulta ao Postgres: fora do event loop
        return Uni.createFrom().item(() -> enrollment.activeMachine(cert))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .flatMap(machine -> {
                    if (machine.isEmpty()) {
                        return CheckResult.rejectUpgrade(403);
                    }
                    ctx.userData().put(EdgeSocket.EDGE_ID, cert.getSubjectX500Principal().getName().substring(3));
                    ctx.userData().put(EdgeSocket.MACHINE, machine.get());
                    return CheckResult.permitUpgrade();
                })
                .onFailure().recoverWithUni(() -> CheckResult.rejectUpgrade(503));
    }
}
