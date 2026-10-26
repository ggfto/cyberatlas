package io.github.ggfto.atlas.hub.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.hub.SourcesResource;
import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.security.RolesAllowed;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regras do MCP (issue #26) que nao dependem de rodar o hub: sem logs, so tools somente leitura, mesmos papeis do
 * REST e reflexao registrada para o nativo.
 */
class McpArchitectureTest {

    static List<Class<?>> mcpClasses() {
        List<Class<?>> out = new ArrayList<>(List.of(AtlasMcpTools.class, McpView.class));
        out.addAll(Arrays.asList(McpView.class.getDeclaredClasses()));
        return out;
    }

    /** Nada do MCP toca em log: nem campo, nem construtor, nem metodo com tipo de hub.logs ou Log* do core. */
    @Test
    void noLogTypeReachesTheMcp() {
        for (Class<?> c : mcpClasses()) {
            List<Type> types = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                types.add(f.getGenericType());
            }
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                types.addAll(Arrays.asList(k.getGenericParameterTypes()));
            }
            for (Method m : c.getDeclaredMethods()) {
                types.add(m.getGenericReturnType());
                types.addAll(Arrays.asList(m.getGenericParameterTypes()));
            }
            for (Type t : types) {
                String name = t.getTypeName();
                assertFalse(name.contains("atlas.hub.logs.") || name.matches(".*atlas\\.core\\.Log[A-Z].*"),
                        c.getSimpleName() + " depende de log: " + name);
            }
        }
    }

    @Test
    void onlyReadOnlyToolsNoResourcesNorPrompts() {
        List<String> tools = new ArrayList<>();
        for (Method m : AtlasMcpTools.class.getDeclaredMethods()) {
            assertFalse(m.isAnnotationPresent(Resource.class) || m.isAnnotationPresent(Prompt.class), "MCP so expoe tools: " + m);
            Tool t = m.getAnnotation(Tool.class);
            if (t == null) {
                continue;
            }
            tools.add(t.name());
            assertTrue(t.annotations().readOnlyHint(), t.name() + " sem readOnlyHint");
            assertFalse(t.annotations().destructiveHint(), t.name() + " com destructiveHint");
            assertFalse(t.annotations().openWorldHint(), t.name() + " com openWorldHint");
            assertTrue(m.getReturnType().getEnclosingClass() == McpView.class, t.name() + " devolve tipo fora do McpView: " + m.getReturnType());
        }
        tools.sort(null);
        assertEquals(List.of("get_history", "get_node", "get_topology", "list_problems", "list_sources"), tools);
    }

    /** Mesmos papeis do REST de leitura (o /api/sources e o /api/history usam este @RolesAllowed). */
    @Test
    void sameRolesAsTheRestReadEndpoints() throws Exception {
        String[] rest = SourcesResource.class.getMethod("sources").getAnnotation(RolesAllowed.class).value();
        assertArrayEquals(rest, AtlasMcpTools.class.getAnnotation(RolesAllowed.class).value());
    }

    /** No nativo o Jackson so serializa record registrado (ver NativeReflectionTest). */
    @Test
    void everyMcpRecordIsRegisteredForReflection() {
        for (Class<?> c : McpView.class.getDeclaredClasses()) {
            if (c.isRecord()) {
                assertTrue(c.isAnnotationPresent(RegisterForReflection.class), "record do MCP sem reflexao no nativo: " + c.getSimpleName());
            }
        }
    }
}
