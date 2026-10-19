package io.github.ggfto.atlas.hub.layout;

import io.github.ggfto.atlas.hub.auth.Roles;
import io.github.ggfto.atlas.hub.store.LayoutStore;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Layout manual (design §3.2). Nao existe "salvar o canvas": cada no e um PATCH com {@code If-Match: "<rev>"}
 * (0 = o no ainda nao tem layout manual). Rev divergente = 409 com o valor atual, e o front faz o merge. Em lote,
 * cada item tem o seu resultado, nunca tudo-ou-nada. Ler: viewer; mover: atlas-admins.
 */
@Path("/api/layout")
@Produces(MediaType.APPLICATION_JSON)
public class LayoutResource {

    static final double LIMIT = 1_000_000;
    static final int MAX_KEY = 512;
    static final int MAX_BATCH = 200;

    @RegisterForReflection
    public record Move(Double x, Double y, Boolean pinned) {
    }

    @RegisterForReflection
    public record BatchItem(String nodeKey, Long rev, Double x, Double y, Boolean pinned) {
    }

    @RegisterForReflection
    public record ItemResult(String nodeKey, int status, LayoutStore.Position position, LayoutStore.Position current, String error) {
    }

    @RegisterForReflection
    public record Conflict(LayoutStore.Position current) {
    }

    private final LayoutStore store;
    private final SecurityIdentity identity;

    LayoutResource(LayoutStore store, SecurityIdentity identity) {
        this.store = store;
        this.identity = identity;
    }

    @GET
    @RolesAllowed({Roles.VIEWER, Roles.ADMIN})
    public List<LayoutStore.Position> all() {
        return store.all();
    }

    @PATCH
    @Path("/nodes/{key: .+}")
    @RolesAllowed(Roles.ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response move(@PathParam("key") String key, @HeaderParam("If-Match") String ifMatch, Move body) {
        long rev = rev(ifMatch);
        String error = invalid(key, body == null ? null : body.x(), body == null ? null : body.y());
        if (error != null) {
            throw bad(400, error);
        }
        return switch (store.put(key, body.x(), body.y(), Boolean.TRUE.equals(body.pinned()), rev, who())) {
            case LayoutStore.Result.Saved s -> Response.ok(s.position()).tag(String.valueOf(s.position().rev())).build();
            case LayoutStore.Result.Conflict c -> Response.status(409).entity(new Conflict(c.current())).build();
        };
    }

    @PATCH
    @Path("/nodes")
    @RolesAllowed(Roles.ADMIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public List<ItemResult> moveMany(List<BatchItem> items) {
        if (items == null || items.size() > MAX_BATCH) {
            throw bad(400, "lote de 1 a " + MAX_BATCH + " nos");
        }
        List<ItemResult> out = new ArrayList<>();
        for (BatchItem i : items) {
            String error = i == null ? "item vazio" : i.rev() == null || i.rev() < 0 ? "rev obrigatoria" : invalid(i.nodeKey(), i.x(), i.y());
            if (error != null) {
                out.add(new ItemResult(i == null ? null : i.nodeKey(), 400, null, null, error));
                continue;
            }
            out.add(switch (store.put(i.nodeKey(), i.x(), i.y(), Boolean.TRUE.equals(i.pinned()), i.rev(), who())) {
                case LayoutStore.Result.Saved s -> new ItemResult(i.nodeKey(), 200, s.position(), null, null);
                case LayoutStore.Result.Conflict c -> new ItemResult(i.nodeKey(), 409, null, c.current(), null);
            });
        }
        return out;
    }

    @DELETE
    @Path("/nodes/{key: .+}")
    @RolesAllowed(Roles.ADMIN)
    public Response reset(@PathParam("key") String key, @HeaderParam("If-Match") String ifMatch) {
        long rev = rev(ifMatch);
        if (store.delete(key, rev)) {
            return Response.noContent().build();
        }
        return Response.status(409).entity(new Conflict(store.get(key).orElse(null))).build();
    }

    private String who() {
        return identity.getPrincipal().getName();
    }

    /** {@code "3"}, {@code W/"3"} ou {@code 3}; sem o cabecalho, 428 (nao existe escrita sem versao). */
    static long rev(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw bad(428, "If-Match obrigatorio (rev atual do no, 0 para criar)");
        }
        String v = ifMatch.trim();
        if (v.startsWith("W/")) {
            v = v.substring(2);
        }
        v = v.replace("\"", "");
        if (!v.matches("[0-9]{1,18}")) {
            throw bad(400, "If-Match invalido");
        }
        return Long.parseLong(v);
    }

    static String invalid(String key, Double x, Double y) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY) {
            return "nodeKey invalida";
        }
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > LIMIT || Math.abs(y) > LIMIT) {
            return "x e y obrigatorios, finitos e ate " + (long) LIMIT;
        }
        return null;
    }

    private static WebApplicationException bad(int status, String message) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build());
    }
}
