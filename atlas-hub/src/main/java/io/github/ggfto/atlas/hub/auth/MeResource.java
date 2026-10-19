package io.github.ggfto.atlas.hub.auth;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** Quem esta logado e com quais papeis do atlas (o front decide o que mostrar; o hub decide o que vale). */
@Path("/api/me")
public class MeResource {

    @RegisterForReflection
    public record Me(String name, List<String> roles) {
    }

    private final SecurityIdentity identity;

    MeResource(SecurityIdentity identity) {
        this.identity = identity;
    }

    @GET
    @RolesAllowed({Roles.VIEWER, Roles.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
    public Me me() {
        // so os papeis do atlas: os outros grupos do Authentik da pessoa nao sao da conta do atlas
        return new Me(identity.getPrincipal().getName(),
                identity.getRoles().stream().filter(r -> r.equals(Roles.VIEWER) || r.equals(Roles.ADMIN)).sorted().toList());
    }
}
