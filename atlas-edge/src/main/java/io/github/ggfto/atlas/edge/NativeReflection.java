package io.github.ggfto.atlas.edge;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.LogLine;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.protocol.Frame;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Records do fio (frames e o fragmento dentro do Snapshot) para o Jackson no binario nativo do edge. */
@RegisterForReflection(targets = {Fragment.class, Node.class, Edge.class, Observation.class, State.class, NodeKind.class,
        EdgeKind.class, Frame.class, Frame.Hello.class, Frame.Welcome.class, Frame.LocalCheck.class, Frame.Reject.class,
        Frame.Snapshot.class, Frame.Resync.class, Frame.Heartbeat.class, Frame.LogOpen.class, Frame.LogLines.class,
        Frame.Credit.class, Frame.LogClose.class, LogLine.class})
final class NativeReflection {
}
