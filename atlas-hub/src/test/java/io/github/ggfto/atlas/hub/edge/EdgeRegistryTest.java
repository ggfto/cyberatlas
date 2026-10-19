package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Registro por sessao (licao do hive-agent, agent-protocol.md): a reconexao chega antes do close da sessao meio
 * aberta; o close atrasado da sessao velha nao pode tirar o edge que acabou de voltar.
 */
class EdgeRegistryTest {

    @Test
    void staleCloseOfTheOldSessionDoesNotEvictTheNewOne() {
        EdgeRegistry r = new EdgeRegistry();
        r.open("edge-host", "s-1");
        r.open("edge-host", "s-2");
        assertFalse(r.close("edge-host", "s-1"), "close da sessao velha e um nao-evento");
        assertEquals(Optional.of("s-2"), r.session("edge-host"));
        assertTrue(r.close("edge-host", "s-2"));
        assertEquals(Optional.empty(), r.session("edge-host"));
    }

    @Test
    void closeOfUnknownEdgeIsHarmless() {
        assertFalse(new EdgeRegistry().close("edge-x", "s-9"));
    }
}
