package io.github.ggfto.atlas.hub;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.NodeStatus;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.SourceState;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.core.TopologyNode;
import io.github.ggfto.atlas.protocol.Frame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * O atlas-core e o atlas-protocol sao so JDK/Jackson: no binario nativo o Jackson precisa destes records por
 * reflexao (o mapa para o browser e os frames do WebSocket dos edges). O NativeReflectionTest confere a lista.
 */
@RegisterForReflection(targets = {Topology.class, TopologyNode.class, Node.class, NodeStatus.class, SourceState.class,
        Edge.class, State.class, NodeKind.class, EdgeKind.class, Fragment.class, Observation.class,
        Frame.class, Frame.Hello.class, Frame.Welcome.class, Frame.LocalCheck.class, Frame.Reject.class,
        Frame.Snapshot.class, Frame.Resync.class, Frame.Heartbeat.class})
final class NativeReflection {
}
