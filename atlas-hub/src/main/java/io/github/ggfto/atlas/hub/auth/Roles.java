package io.github.ggfto.atlas.hub.auth;

/** Grupos do Authentik que viram papel (claim {@code groups}). Admin inclui tudo do viewer. */
public final class Roles {

    public static final String VIEWER = "atlas-viewers";
    public static final String ADMIN = "atlas-admins";

    private Roles() {
    }
}
