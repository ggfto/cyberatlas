package io.github.ggfto.atlas.hub.mcp;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.Redactor;
import io.github.ggfto.atlas.core.Sanitizer;
import io.github.ggfto.atlas.core.SourceState;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyNode;
import io.github.ggfto.atlas.hub.TopologyService;
import io.github.ggfto.atlas.hub.store.StatusHistory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * O que sai do MCP (issue #26): so estes records, montados a partir do que os endpoints REST de leitura ja devolvem,
 * nunca {@link Node} ou fragmento cru. Atributos passam pelo {@link Sanitizer} (de novo: o snapshot ja vem
 * sanitizado, e aqui a garantia fica no proprio MCP). Texto livre (motivo, drift, erro de fonte) vai para um LLM de
 * terceiro, entao leva a mascara de IPv4 do Sanitizer, a redacao de segredos do {@link Redactor} e um corte de
 * tamanho. Nenhum record carrega log.
 */
public final class McpView {

    /** Corte de texto livre: motivo de fonte nao precisa de mais que isto, e corta despejo de stack/HTML. */
    static final int MAX_TEXT = 300;

    private McpView() {
    }

    @RegisterForReflection
    public record SourceView(String source, String state, String reason, Instant observedAt, boolean fresh) {
    }

    @RegisterForReflection
    public record NodeView(String key, String kind, String name, String machine, Map<String, String> attributes,
                           String state, String reason, List<SourceView> sources, List<String> drift) {
    }

    @RegisterForReflection
    public record EdgeView(String from, String to, String kind) {
    }

    @RegisterForReflection
    public record TopologyView(Instant generatedAt, List<NodeView> nodes, List<EdgeView> edges) {
    }

    @RegisterForReflection
    public record NodeDetail(NodeView node, List<EdgeView> edges) {
    }

    /** {@code unknown}: quantos nos estao sem fonte fresca (nao entram na lista, mas a contagem diz se ha muitos). */
    @RegisterForReflection
    public record Problems(Instant generatedAt, List<NodeView> problems, int unknown) {
    }

    @RegisterForReflection
    public record ChangeView(String from, String to, String reason, Instant at) {
    }

    @RegisterForReflection
    public record History(String key, List<ChangeView> changes) {
    }

    @RegisterForReflection
    public record SourceHealth(String name, boolean ok, String error) {
    }

    @RegisterForReflection
    public record Sources(List<SourceHealth> sources) {
    }

    /** Mascara de texto livre so do MCP: IPv4 como no Sanitizer, segredos como no Redactor, e o corte. */
    static String text(String s) {
        if (s == null) {
            return null;
        }
        String out = Redactor.DEFAULT.redact(Sanitizer.maskIpv4(s));
        return out.length() <= MAX_TEXT ? out : out.substring(0, MAX_TEXT) + "...";
    }

    static NodeView node(TopologyNode t) {
        Node n = Sanitizer.DEFAULT.sanitize(t.node());
        List<SourceView> sources = t.status().sources().stream().map(McpView::sourceState).toList();
        return new NodeView(n.key(), n.kind().name(), n.name(), n.machine(), n.attributes(), t.status().state().name(),
                text(t.status().reason()), sources, t.drift().stream().map(McpView::text).toList());
    }

    static SourceView sourceState(SourceState s) {
        return new SourceView(s.source(), s.state().name(), text(s.reason()), s.observedAt(), s.fresh());
    }

    static EdgeView edge(Edge e) {
        return new EdgeView(e.from(), e.to(), e.kind().name());
    }

    static TopologyView topology(Topology t) {
        return new TopologyView(t.generatedAt(), t.nodes().stream().map(McpView::node).toList(),
                t.edges().stream().map(McpView::edge).toList());
    }

    static ChangeView change(StatusHistory.Change c) {
        return new ChangeView(c.from(), c.to(), text(c.reason()), c.at());
    }

    static SourceHealth sourceHealth(TopologyService.SourceStatus s) {
        return new SourceHealth(s.name(), s.error() == null, text(s.error()));
    }
}
