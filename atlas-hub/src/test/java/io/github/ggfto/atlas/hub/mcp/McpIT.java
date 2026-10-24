package io.github.ggfto.atlas.hub.mcp;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** O MCP contra o jar e, no -Pnative, contra o binario nativo (criterio "funciona no nativo" da issue #26). */
@QuarkusIntegrationTest
class McpIT extends McpTest {
}
