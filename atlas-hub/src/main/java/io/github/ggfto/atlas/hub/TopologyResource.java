package io.github.ggfto.atlas.hub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ggfto.atlas.core.Topology;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import java.time.Duration;
import java.time.Instant;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * O mapa inteiro, ja sanitizado, e o stream SSE (design §3.4): {@code event: snapshot} ao conectar (ou
 * {@code patch} desde o Last-Event-ID), {@code event: patch} a cada mudanca e um comentario a cada 20 s (o tunel
 * da Cloudflare corta conexao ociosa por volta de 100 s). Quando o token que abriu o stream vence, {@code event: end}
 * ({@code {"reason":"auth"}}) e o stream fecha (issue #20): o front confere a sessao, que o hub renova pelo refresh
 * token ou responde 401 (e o /embed oferece o login).
 */
@Path("/api/topology")
public class TopologyResource {

    static final Duration HEARTBEAT = Duration.ofSeconds(20);

    /** exp (epoch s) do token que autenticou, posto pelo OIDC do Quarkus: o ID token na sessao, o access token no bearer. */
    static final String EXPIRE_TIME = "quarkus.identity.expire-time";

    /**
     * O end sai um pouco depois do exp: a conferencia do front tem de chegar com o token ja vencido, para o hub
     * renovar (refresh-expired) em vez de aceitar o token velho e reabrir um stream que fecharia em seguida.
     */
    static final Duration END_SKEW = Duration.ofSeconds(2);

    private final TopologyService service;
    private final TopologyStream stream;
    private final ObjectMapper json;
    private final SecurityIdentity identity;

    TopologyResource(TopologyService service, TopologyStream stream, ObjectMapper json, SecurityIdentity identity) {
        this.service = service;
        this.stream = stream;
        this.json = json;
        this.identity = identity;
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
        Multi<OutboundSseEvent> updates = stream.follow(lastEventId).map(u -> switch (u) {
            case TopologyStream.Initial.Snapshot s -> event(sse, "snapshot", s.version(), s.topology());
            case TopologyStream.Initial.Patch p -> event(sse, "patch", p.patch().version(), p.patch());
            case TopologyStream.Initial.UpToDate d -> throw new IllegalStateException("follow nao emite UpToDate");
        });
        Multi<OutboundSseEvent> pings = Multi.createFrom().ticks().every(HEARTBEAT).map(t -> sse.newEventBuilder().comment("ping").build());
        Multi<OutboundSseEvent> body = Multi.createBy().merging().streams(updates, pings);
        Duration left = untilExpiry(identity, Instant.now());
        if (left == null) {
            return body; // identidade sem exp (break-glass): o stream vive ate o cliente sair
        }
        return Multi.createBy().concatenating().streams(body.select().first(left),
                Multi.createFrom().item(() -> sse.newEventBuilder().name("end").data("{\"reason\":\"auth\"}").build()));
    }

    static Duration untilExpiry(SecurityIdentity id, Instant now) {
        if (!(id.getAttribute(EXPIRE_TIME) instanceof Number exp)) {
            return null;
        }
        Duration left = Duration.between(now, Instant.ofEpochSecond(exp.longValue())).plus(END_SKEW);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private OutboundSseEvent event(Sse sse, String name, long version, Object payload) {
        try {
            return sse.newEventBuilder().name(name).id(String.valueOf(version)).data(json.writeValueAsString(payload)).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("mapa nao serializavel", e);
        }
    }
}
