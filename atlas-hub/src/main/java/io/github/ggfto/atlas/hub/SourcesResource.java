package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.hub.auth.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Saude das fontes do hub: cada uma com o ultimo erro (ex.: "github ggfto/infra/vps/services.yml@stable: HTTP 404:
 * ... repo privado ..."). E o lugar onde uma fonte quebrada aparece, em vez de so sumir do mapa. Sem segredo: as
 * mensagens das fontes nunca levam token.
 */
@Path("/api/sources")
public class SourcesResource {

    private final TopologyService topology;

    SourcesResource(TopologyService topology) {
        this.topology = topology;
    }

    @GET
    @RolesAllowed({Roles.VIEWER, Roles.ADMIN})
    @Produces(MediaType.APPLICATION_JSON)
    public List<TopologyService.SourceStatus> sources() {
        return topology.sources();
    }
}
