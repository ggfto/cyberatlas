package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.auth.CsrfExempt;
import io.github.ggfto.atlas.hub.store.AuditLog;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * Enrollment do edge (so na porta de edge, pela EdgePortPolicy): token de uso unico + CSR no corpo; devolve o
 * certificado assinado pela CA do atlas e a propria CA. Isento do anti-CSRF: nao ha cookie aqui, quem prova e o token.
 */
@CsrfExempt
@Path("/api/enrollments")
public class EnrollmentResource {

    public record EnrollRequest(String token, String csr) {
    }

    private final EdgeHub hub;
    private final AuditLog audit;

    EnrollmentResource(EdgeHub hub, AuditLog audit) {
        this.hub = hub;
        this.audit = audit;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response enroll(EnrollRequest body) {
        if (hub.enrollment().isEmpty()) {
            return Response.status(503).entity(Map.of("error", "CA do atlas nao configurada")).build();
        }
        try {
            EnrollmentService.Issued i = hub.enrollment().get().enroll(body == null ? null : body.token(), body == null ? "" : body.csr());
            audit.record(i.edgeId(), "enrollment", i.edgeId());
            return Response.ok(Map.of("edgeId", i.edgeId(), "certificatePem", i.certificatePem(), "caPem", i.caPem())).build();
        } catch (EnrollmentException e) {
            audit.record("anonimo", "enrollment-recusado", e.getMessage());
            return Response.status(403).entity(Map.of("error", e.getMessage())).build();
        }
    }
}
