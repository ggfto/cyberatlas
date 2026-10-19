package io.github.ggfto.atlas.hub.spike;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** Endpoint do spike: so responde se a {@link BreakGlassPortPolicy} deixar passar. */
@Path("/spike/breakglass")
public class SpikeResource {

    @GET
    public String ping() {
        return "breakglass";
    }
}
