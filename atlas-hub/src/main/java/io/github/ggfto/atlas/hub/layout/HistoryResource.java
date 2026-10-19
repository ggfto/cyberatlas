package io.github.ggfto.atlas.hub.layout;

import io.github.ggfto.atlas.hub.auth.Roles;
import io.github.ggfto.atlas.hub.store.StatusHistory;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** Mudancas de estado de um no nos ultimos 7 dias, da mais nova para a mais velha (gaveta: "mudou ha X"). */
@Path("/api/history")
public class HistoryResource {

    private final StatusHistory history;

    HistoryResource(StatusHistory history) {
        this.history = history;
    }

    @GET
    @RolesAllowed({Roles.VIEWER, Roles.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
    public List<StatusHistory.Change> of(@QueryParam("node") String node, @QueryParam("limit") @DefaultValue("50") int limit) {
        if (node == null || node.isBlank()) {
            throw new BadRequestException("parametro node obrigatorio");
        }
        return history.of(node, limit);
    }
}
