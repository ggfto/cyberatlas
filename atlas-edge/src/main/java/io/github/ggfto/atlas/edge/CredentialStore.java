package io.github.ggfto.atlas.edge;

import io.github.ggfto.atlas.protocol.EdgeCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/** Chave, certificado e CA do edge num diretorio de estado (volume); a chave so para o dono (0600). */
public final class CredentialStore {

    private final Path dir;

    public CredentialStore(Path dir) {
        this.dir = dir;
    }

    public Optional<EdgeCredentials> load() {
        Path key = dir.resolve("edge.key");
        Path cert = dir.resolve("edge.crt");
        Path ca = dir.resolve("ca.crt");
        if (!Files.isRegularFile(key) || !Files.isRegularFile(cert) || !Files.isRegularFile(ca)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new EdgeCredentials(Files.readString(key), Files.readString(cert), Files.readString(ca)));
        } catch (IOException e) {
            throw new UncheckedIOException("credenciais do edge ilegiveis em " + dir, e);
        }
    }

    public void save(EdgeCredentials c) throws IOException {
        Files.createDirectories(dir);
        Path key = dir.resolve("edge.key");
        Files.deleteIfExists(key);
        Files.createFile(key, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(key, c.keyPem(), StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(dir.resolve("edge.crt"), c.certPem());
        Files.writeString(dir.resolve("ca.crt"), c.caPem());
    }
}
