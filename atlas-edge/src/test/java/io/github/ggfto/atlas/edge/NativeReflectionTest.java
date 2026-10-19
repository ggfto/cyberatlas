package io.github.ggfto.atlas.edge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.protocol.Frame;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** O edge nativo le Welcome/Reject e escreve Hello/Snapshot: todos os records do fio registrados para o Jackson. */
class NativeReflectionTest {

    @Test
    void everyWireRecordIsRegistered() {
        Set<Class<?>> r = Set.of(NativeReflection.class.getAnnotation(RegisterForReflection.class).targets());
        for (Class<?> c : Frame.class.getPermittedSubclasses()) {
            assertTrue(r.contains(c), "frame sem reflexao no nativo: " + c.getSimpleName());
        }
        for (Class<?> c : List.of(Frame.class, Frame.LocalCheck.class, Fragment.class, Node.class, Edge.class, Observation.class)) {
            assertTrue(r.contains(c), "record sem reflexao no nativo: " + c.getSimpleName());
        }
    }
}
