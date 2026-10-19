package io.github.ggfto.atlas.adapters.hub;

import io.github.ggfto.atlas.core.ProbeResult;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * O que uma rota publica deveria responder (design §1.4). Rota com {@code auth: true} so esta bem se redireciona
 * para o host de login: isso prova Cloudflare + Traefik + forwardAuth, nao o app (o upstream vem do edge, F2).
 */
public sealed interface Expectation {

    String SOURCE = "check:publico";

    /** Rota atras do Authentik: espera 3xx para {@code authHost}. */
    record Protected(String authHost) implements Expectation {
        @Override
        public Observation classify(String nodeKey, ProbeResult r, Instant at, Duration ttl) {
            if (r.error() != null) {
                return obs(nodeKey, State.OFFLINE, "sem resposta: " + r.error(), at, ttl);
            }
            int s = r.status();
            if (isRedirect(s)) {
                String host = locationHost(r.location());
                return authHost.equalsIgnoreCase(host)
                        ? obs(nodeKey, State.ONLINE, "protegida: redireciona ao login", at, ttl)
                        : obs(nodeKey, State.DEGRADED, "redireciona para fora do login (" + describe(r.location(), host) + ")", at, ttl);
            }
            if (s >= 200 && s < 300) {
                return obs(nodeKey, State.DEGRADED, "rota protegida respondeu sem login (" + s + ")", at, ttl);
            }
            return other(nodeKey, s, at, ttl);
        }
    }

    /** Rota sem parede: espera 2xx/3xx, mas nao um redirect para o login. */
    record Open(String authHost) implements Expectation {
        @Override
        public Observation classify(String nodeKey, ProbeResult r, Instant at, Duration ttl) {
            if (r.error() != null) {
                return obs(nodeKey, State.OFFLINE, "sem resposta: " + r.error(), at, ttl);
            }
            int s = r.status();
            if (isRedirect(s)) {
                return authHost.equalsIgnoreCase(locationHost(r.location()))
                        ? obs(nodeKey, State.DEGRADED, "rota sem auth pede login", at, ttl)
                        : obs(nodeKey, State.ONLINE, "redireciona (" + s + ")", at, ttl);
            }
            if (s >= 200 && s < 300) {
                return obs(nodeKey, State.ONLINE, String.valueOf(s), at, ttl);
            }
            return other(nodeKey, s, at, ttl);
        }
    }

    Observation classify(String nodeKey, ProbeResult result, Instant at, Duration ttl);

    private static Observation other(String nodeKey, int s, Instant at, Duration ttl) {
        return s >= 500
                ? obs(nodeKey, State.OFFLINE, "erro " + s, at, ttl)
                : obs(nodeKey, State.DEGRADED, "resposta inesperada (" + s + ")", at, ttl);
    }

    private static boolean isRedirect(int s) {
        return s == 301 || s == 302 || s == 303 || s == 307 || s == 308;
    }

    private static String locationHost(String location) {
        if (location == null) {
            return null;
        }
        try {
            String host = URI.create(location.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(String location, String host) {
        if (location == null) {
            return "sem Location";
        }
        return host == null ? "relativo" : host;
    }

    private static Observation obs(String nodeKey, State state, String reason, Instant at, Duration ttl) {
        return new Observation(nodeKey, SOURCE, state, reason, at, ttl);
    }
}
