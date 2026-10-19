package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.hub.auth.Roles;
import io.github.ggfto.atlas.hub.store.AuditLog;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/**
 * Criar, re-enrollar e revogar edges (so atlas-admins; {@code atlas.edges.admin-enabled=false} desliga). O token
 * sai so na resposta da criacao, nunca em log nem na auditoria.
 */
@Path("/api/edges")
@RolesAllowed(Roles.ADMIN)
public class EdgeAdminResource {

    public record CreateEdge(String machine) {
    }

    private final EdgeHub hub;
    private final AuditLog audit;
    private final SecurityIdentity identity;

    EdgeAdminResource(EdgeHub hub, AuditLog audit, SecurityIdentity identity) {
        this.hub = hub;
        this.audit = audit;
        this.identity = identity;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateEdge body) {
        EnrollmentService svc = service();
        try {
            EnrollmentService.NewEdge e = svc.createEdge(body == null ? null : body.machine());
            audit.record(who(), "edge-criado", e.edgeId());
            return Response.status(201).entity(Map.of("edgeId", e.edgeId(), "token", e.token(), "expiresAt", e.expiresAt().toString())).build();
        } catch (EnrollmentException ex) {
            return Response.status(400).entity(Map.of("error", ex.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/re-enroll")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reEnroll(@PathParam("id") String id) {
        try {
            EnrollmentService.NewEdge e = service().reEnroll(id);
            audit.record(who(), "edge-re-enroll", e.edgeId());
            return Response.status(201).entity(Map.of("edgeId", e.edgeId(), "token", e.token(), "expiresAt", e.expiresAt().toString())).build();
        } catch (EnrollmentException ex) {
            return Response.status(404).entity(Map.of("error", ex.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/revoke")
    public Response revoke(@PathParam("id") String id) {
        service().revoke(id);
        audit.record(who(), "edge-revogado", id);
        return Response.noContent().build();
    }

    private String who() {
        return identity.getPrincipal().getName();
    }

    private EnrollmentService service() {
        if (!hub.config().adminEnabled()) {
            throw new NotFoundException();
        }
        return hub.enrollment().orElseThrow(() -> new jakarta.ws.rs.ServiceUnavailableException("CA do atlas nao configurada"));
    }
}
