package io.github.ggfto.atlas.hub.mcp;

import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyNode;
import io.github.ggfto.atlas.hub.TopologyService;
import io.github.ggfto.atlas.hub.auth.Roles;
import io.github.ggfto.atlas.hub.store.StatusHistory;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.Tool.Annotations;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

/**
 * Tools MCP somente leitura (issue #26), em {@code /mcp}. Cada uma chama o mesmo servico do endpoint REST de leitura
 * equivalente ({@code /api/topology}, {@code /api/history}, {@code /api/sources}), com os mesmos papeis, e devolve so
 * os records do {@link McpView}. Sem logs (nada de {@code hub.logs} aqui; o McpArchitectureTest confere) e sem
 * escrita. So entra por bearer: o AtlasAuthMechanism recusa cookie de sessao no {@code /mcp}.
 */
@RolesAllowed({Roles.VIEWER, Roles.ADMIN})
public class AtlasMcpTools {

    private final TopologyService topology;
    private final StatusHistory history;

    AtlasMcpTools(TopologyService topology, StatusHistory history) {
        this.topology = topology;
        this.history = history;
    }

    @Tool(name = "get_topology", structuredContent = true,
            description = "Mapa inteiro do Cyber Atlas: nos (chave, tipo, maquina, atributos permitidos, estado, motivo, fontes, drift) e arestas.",
            annotations = @Annotations(title = "Mapa", readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    McpView.TopologyView getTopology() {
        return McpView.topology(topology.snapshot());
    }

    @Tool(name = "get_node", structuredContent = true,
            description = "Um no pela chave (ex.: route:ha.gf2.in, service:vps/homeassistant): estado, motivo, o que cada fonte disse, drift e as arestas dele.",
            annotations = @Annotations(title = "No", readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    McpView.NodeDetail getNode(@ToolArg(description = "Chave do no, como aparece em get_topology/list_problems") String key) {
        Topology t = topology.snapshot();
        TopologyNode node = t.nodes().stream().filter(n -> n.node().key().equals(key)).findFirst()
                .orElseThrow(() -> new ToolCallException("no nao encontrado"));
        return new McpView.NodeDetail(McpView.node(node), t.edges().stream()
                .filter(e -> e.from().equals(key) || e.to().equals(key)).map(McpView::edge).toList());
    }

    @Tool(name = "list_problems", structuredContent = true,
            description = "Problemas atuais: nos OFFLINE ou DEGRADED e nos com selo de drift. Nos UNKNOWN (sem fonte fresca) so entram na contagem.",
            annotations = @Annotations(title = "Problemas atuais", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    McpView.Problems listProblems() {
        Topology t = topology.snapshot();
        List<McpView.NodeView> problems = t.nodes().stream().filter(AtlasMcpTools::problem).map(McpView::node).toList();
        int unknown = (int) t.nodes().stream().filter(n -> n.status().state() == State.UNKNOWN).count();
        return new McpView.Problems(t.generatedAt(), problems, unknown);
    }

    @Tool(name = "get_history", structuredContent = true,
            description = "Mudancas de estado de um no nos ultimos 7 dias, da mais nova para a mais velha.",
            annotations = @Annotations(title = "Historico do no", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    McpView.History getHistory(@ToolArg(description = "Chave do no") String key,
            @ToolArg(description = "Maximo de mudancas (1 a 500)", required = false, defaultValue = "50") int limit) {
        if (key == null || key.isBlank()) {
            throw new ToolCallException("key obrigatoria");
        }
        return new McpView.History(key, history.of(key, limit).stream().map(McpView::change).toList());
    }

    @Tool(name = "list_sources", structuredContent = true,
            description = "Fontes coletadas pelo hub (services.yml, Cloudflare, Argo, checks) e o ultimo erro de cada uma.",
            annotations = @Annotations(title = "Fontes", readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    McpView.Sources listSources() {
        return new McpView.Sources(topology.sources().stream().map(McpView::sourceHealth).toList());
    }

    static boolean problem(TopologyNode n) {
        State s = n.status().state();
        return s == State.OFFLINE || s == State.DEGRADED || !n.drift().isEmpty();
    }
}
