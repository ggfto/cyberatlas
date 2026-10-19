package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.Topology;
import io.github.ggfto.atlas.protocol.Frame;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * No binario nativo o Jackson so enxerga record registrado para reflexao (a CI da F2 pegou o Hello sem
 * desserializar e o Reject saindo sem campos). Este teste pega na JVM: todo frame do sealed Frame e todo record
 * que vai no fio tem de estar registrado.
 */
class NativeReflectionTest {

    static Set<Class<?>> registered() {
        return Set.of(NativeReflection.class.getAnnotation(RegisterForReflection.class).targets());
    }

    static List<Class<?>> frames() {
        List<Class<?>> out = new ArrayList<>(List.of(Frame.class.getPermittedSubclasses()));
        out.add(Frame.LocalCheck.class);
        return out;
    }

    @Test
    void everyWireRecordIsRegistered() {
        Set<Class<?>> r = registered();
        for (Class<?> c : frames()) {
            assertTrue(r.contains(c), "frame sem reflexao no nativo: " + c.getSimpleName());
        }
        for (Class<?> c : List.of(Frame.class, Fragment.class, Observation.class, Topology.class, io.github.ggfto.atlas.core.TopologyPatch.class)) {
            assertTrue(r.contains(c), "record sem reflexao no nativo: " + c.getSimpleName());
        }
    }
}
