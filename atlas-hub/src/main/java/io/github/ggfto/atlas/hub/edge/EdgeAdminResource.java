package io.github.ggfto.atlas.hub.edge;

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
 * Criar, re-enrollar e revogar edges. So existe com {@code atlas.edges.admin-enabled=true} ate a F5 (login +
 * papel atlas-admins); por padrao responde 404. O token sai so na resposta da criacao, nunca em log.
 */
@Path("/api/edges")
public class EdgeAdminResource {

    public record CreateEdge(String machine) {
    }

    private final EdgeHub hub;

    EdgeAdminResource(EdgeHub hub) {
        this.hub = hub;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(CreateEdge body) {
        EnrollmentService svc = service();
        try {
            EnrollmentService.NewEdge e = svc.createEdge(body == null ? null : body.machine());
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
            return Response.status(201).entity(Map.of("edgeId", e.edgeId(), "token", e.token(), "expiresAt", e.expiresAt().toString())).build();
        } catch (EnrollmentException ex) {
            return Response.status(404).entity(Map.of("error", ex.getMessage())).build();
        }
    }

    @POST
    @Path("/{id}/revoke")
    public Response revoke(@PathParam("id") String id) {
        service().revoke(id);
        return Response.noContent().build();
    }

    private EnrollmentService service() {
        if (!hub.config().adminEnabled()) {
            throw new NotFoundException();
        }
        return hub.enrollment().orElseThrow(() -> new jakarta.ws.rs.ServiceUnavailableException("CA do atlas nao configurada"));
    }
}
