package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** O SSE no binario nativo: snapshot inicial (com drift e status serializados) e retomada com id desconhecido. */
@QuarkusIntegrationTest
@TestProfile(FixtureProfile.class)
class TopologyStreamIT {

    @TestHTTPResource("/api/topology/stream")
    URI stream;

    @Test
    void firstEventIsTheSnapshot() throws Exception {
        TopologyStreamTest.Event e = TopologyStreamTest.open(stream, null).poll(15, TimeUnit.SECONDS);
        assertEquals("snapshot", e.name());
        assertTrue(e.data().contains("\"route:ha.gf2.in\"") && e.data().contains("\"drift\""), e.data());
        assertEquals("snapshot", TopologyStreamTest.open(stream, "99999999").poll(15, TimeUnit.SECONDS).name());
    }
}
