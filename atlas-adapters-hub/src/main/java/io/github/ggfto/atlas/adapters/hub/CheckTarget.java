package io.github.ggfto.atlas.adapters.hub;

import java.net.URI;

/** Uma rota publica a checar pela internet, com o que ela deveria responder. */
public record CheckTarget(String nodeKey, URI url, Expectation expectation) {
}
