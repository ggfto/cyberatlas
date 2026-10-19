package io.github.ggfto.atlas.hub.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ggfto.atlas.hub.auth.Roles;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Log ao vivo por SSE (design §3.4): {@code event: line} (LogLine), {@code dropped} ({@code {"n":..}}) e
 * {@code end} ({@code {"reason":..}}), mais um comentario a cada 20 s. So atlas-admins (permissao por caminho e
 * {@code @RolesAllowed}, e de novo no LogService); o limite de streams conta por usuario logado.
 */
@Path("/api/logs")
public class LogResource {

    static final Duration HEARTBEAT = Duration.ofSeconds(20);

    private final LogService logs;
    private final ObjectMapper json;
    private final SecurityIdentity identity;

    LogResource(LogService logs, ObjectMapper json, SecurityIdentity identity) {
        this.logs = logs;
        this.json = json;
        this.identity = identity;
    }

    @GET
    @RolesAllowed(Roles.ADMIN)
    @Path("/targets")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> targets() {
        if (!logs.enabled()) {
            throw new NotFoundException();
        }
        return logs.targets();
    }

    @GET
    @RolesAllowed(Roles.ADMIN)
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> stream(@QueryParam("node") String node, @QueryParam("container") String container,
                                          @QueryParam("tail") @DefaultValue("200") int tail, @QueryParam("filter") String filter,
                                          @QueryParam("regex") @DefaultValue("false") boolean regex, @Context Sse sse) {
        if (!logs.enabled()) {
            throw new NotFoundException();
        }
        if (node == null || node.isBlank()) {
            throw error(400, "parametro node obrigatorio");
        }
        Multi<LogHub.LogEvent> events;
        try {
            events = logs.open(node, container, tail, filter, regex, identity);
        } catch (LogService.BadTarget | IllegalArgumentException e) {
            throw error(400, e.getMessage());
        } catch (LogService.EdgeOffline e) {
            throw error(503, e.getMessage());
        } catch (LogHub.LimitException e) {
            throw error(429, e.getMessage());
        }
        AtomicBoolean done = new AtomicBoolean();
        Multi<OutboundSseEvent> lines = events.map(e -> event(sse, e)).onTermination().invoke(() -> done.set(true));
        Multi<OutboundSseEvent> pings = Multi.createFrom().ticks().every(HEARTBEAT).select().first(t -> !done.get())
                .map(t -> sse.newEventBuilder().comment("ping").build());
        return Multi.createBy().merging().streams(lines, pings);
    }

    private OutboundSseEvent event(Sse sse, LogHub.LogEvent e) {
        try {
            return switch (e) {
                case LogHub.LogEvent.Line l -> sse.newEventBuilder().name("line").data(json.writeValueAsString(l.line())).build();
                case LogHub.LogEvent.Dropped d -> sse.newEventBuilder().name("dropped").data(json.writeValueAsString(Map.of("n", d.n()))).build();
                case LogHub.LogEvent.End end -> sse.newEventBuilder().name("end").data(json.writeValueAsString(Map.of("reason", end.reason()))).build();
            };
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static WebApplicationException error(int status, String message) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build());
    }
}
