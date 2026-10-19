package io.github.ggfto.atlas.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** O hub aceita a versao atual e a anterior (design §1.1); fora disso, motivo explicito. */
class ProtocolVersionTest {

    @Test
    void currentAndPreviousAreAccepted() {
        assertEquals(Optional.empty(), ProtocolVersion.rejection(ProtocolVersion.CURRENT, ProtocolVersion.CURRENT));
        assertEquals(Optional.empty(), ProtocolVersion.rejection(4, 5));
    }

    @Test
    void olderOrNewerIsRejectedWithTheWindow() {
        assertEquals(Optional.of("versao de protocolo 3 fora da janela [4, 5]"), ProtocolVersion.rejection(3, 5));
        assertEquals(Optional.of("versao de protocolo 6 fora da janela [4, 5]"), ProtocolVersion.rejection(6, 5));
    }

    @Test
    void windowNeverGoesBelowOne() {
        assertTrue(ProtocolVersion.rejection(0, 1).isPresent());
        assertEquals(Optional.empty(), ProtocolVersion.rejection(1, 1));
    }
}
