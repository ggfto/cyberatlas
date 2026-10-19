package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.NodeStatus;
import io.github.ggfto.atlas.core.SourceState;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** O atlas-core e so JDK; o Jackson precisa destes records por reflexao no binario nativo. */
@RegisterForReflection(targets = {Topology.class, TopologyNode.class, Node.class, NodeStatus.class, SourceState.class,
        Edge.class, State.class, NodeKind.class, EdgeKind.class})
final class NativeReflection {
}
