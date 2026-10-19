package io.github.ggfto.atlas.hub.edge;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * O ponta a ponta contra o binario nativo: prova a CA (Bouncy Castle), o mTLS da porta de edge, o websockets-next
 * e o codec dos frames no native image.
 */
@QuarkusIntegrationTest
class EdgeEndToEndIT extends EdgeEndToEndTest {
}
