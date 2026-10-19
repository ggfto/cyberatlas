package io.github.ggfto.atlas.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.protocol.EdgeCredentials;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreTest {

    @Test
    void emptyDirHasNoCredentials(@TempDir Path dir) {
        assertEquals(Optional.empty(), new CredentialStore(dir).load());
    }

    @Test
    void savedCredentialsComeBackAndTheKeyIsOwnerOnly(@TempDir Path dir) throws Exception {
        CredentialStore store = new CredentialStore(dir);
        EdgeCredentials c = new EdgeCredentials("-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n",
                "-----BEGIN CERTIFICATE-----\nBB==\n-----END CERTIFICATE-----\n", "-----BEGIN CERTIFICATE-----\nCC==\n-----END CERTIFICATE-----\n");
        store.save(c);
        assertEquals(Optional.of(c), store.load());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("edge.key"))));
        assertTrue(Files.exists(dir.resolve("edge.crt")) && Files.exists(dir.resolve("ca.crt")));
    }

    @Test
    void halfWrittenStateIsNotCredentials(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("edge.crt"), "x");
        assertEquals(Optional.empty(), new CredentialStore(dir).load());
    }
}
