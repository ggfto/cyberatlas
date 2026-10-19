package io.github.ggfto.atlas.hub.auth;

import io.github.ggfto.atlas.hub.store.AuditLog;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** Ultimas entradas da auditoria (so atlas-admins). */
@Path("/api/audit")
public class AuditResource {

    private final AuditLog audit;

    AuditResource(AuditLog audit) {
        this.audit = audit;
    }

    @GET
    @RolesAllowed(Roles.ADMIN)
    @Produces(MediaType.APPLICATION_JSON)
    public List<AuditLog.Entry> recent(@QueryParam("limit") @DefaultValue("100") int limit) {
        return audit.recent(limit);
    }
}
