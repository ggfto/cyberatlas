package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.Topology;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** O mapa inteiro, ja sanitizado. Na F3 ganha o par SSE (snapshot + patch). */
@Path("/api/topology")
public class TopologyResource {

    private final TopologyService service;

    TopologyResource(TopologyService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Topology get() {
        return service.snapshot();
    }
}
