package io.github.ggfto.atlas.hub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ggfto.atlas.core.Topology;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.time.Duration;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * O mapa inteiro, ja sanitizado, e o stream SSE (design §3.4): {@code event: snapshot} ao conectar (ou
 * {@code patch} desde o Last-Event-ID), {@code event: patch} a cada mudanca e um comentario a cada 20 s (o tunel
 * da Cloudflare corta conexao ociosa por volta de 100 s).
 */
@Path("/api/topology")
public class TopologyResource {

    static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private final TopologyService service;
    private final TopologyStream stream;
    private final ObjectMapper json;

    TopologyResource(TopologyService service, TopologyStream stream, ObjectMapper json) {
        this.service = service;
        this.stream = stream;
        this.json = json;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Topology get() {
        return service.snapshot();
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@HeaderParam("Last-Event-ID") String lastEventId, @Context Sse sse) {
        TopologyStream.Initial first = stream.initial(lastEventId);
        Multi<OutboundSseEvent> head = switch (first) {
            case TopologyStream.Initial.Snapshot s -> Multi.createFrom().item(event(sse, "snapshot", s.version(), s.topology()));
            case TopologyStream.Initial.Patch p -> Multi.createFrom().item(event(sse, "patch", p.patch().version(), p.patch()));
            case TopologyStream.Initial.UpToDate u -> Multi.createFrom().empty();
        };
        Multi<OutboundSseEvent> patches = stream.live().map(p -> event(sse, "patch", p.version(), p));
        Multi<OutboundSseEvent> pings = Multi.createFrom().ticks().every(HEARTBEAT).map(t -> sse.newEventBuilder().comment("ping").build());
        return Multi.createBy().concatenating().streams(head, Multi.createBy().merging().streams(patches, pings));
    }

    private OutboundSseEvent event(Sse sse, String name, long version, Object payload) {
        try {
            return sse.newEventBuilder().name(name).id(String.valueOf(version)).data(json.writeValueAsString(payload)).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mapa nao serializavel", e);
        }
    }
}
