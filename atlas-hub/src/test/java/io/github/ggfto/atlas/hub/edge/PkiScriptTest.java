package io.github.ggfto.atlas.hub.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ggfto.atlas.protocol.Csr;
import io.github.ggfto.atlas.protocol.Pem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * deploy/pki/gen-pki.sh (F6): o que o script gera e o que o hub carrega no deploy. A CA assina edge; o cert de
 * servidor da 8443 tem os nomes/IPs pedidos e e emitido pela CA; chave nunca legivel por outro usuario.
 */
class PkiScriptTest {

    static final Path SCRIPT = Path.of("../deploy/pki/gen-pki.sh");

    static Process run(Path out, String... sans) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("bash", SCRIPT.toString(), out.toString()));
        cmd.addAll(List.of(sans));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "script travou");
        return p;
    }

    @Test
    void hubLoadsTheCaAndTheServerCertChainsToIt() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/openssl")) || Files.isExecutable(Path.of("/bin/openssl")), "sem openssl");
        Path out = Files.createTempDirectory("pki").resolve("atlas");
        Process p = run(out, "DNS:atlas.atlas.svc", "IP:10.99.0.1", "IP:172.17.0.1");
        String log = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.exitValue(), log);

        AtlasCa ca = AtlasCa.load(Files.readString(out.resolve("ca.crt")), Files.readString(out.resolve("ca.key")));
        // a CA serve para o enrollment: assina um CSR de edge
        KeyPair edge = Csr.newKeyPair();
        X509Certificate client = ca.issueClient(Csr.parse(Csr.pem(edge, "qualquer")), "edge-vps", Duration.ofDays(1));
        assertEquals("CN=edge-vps", client.getSubjectX500Principal().getName());

        X509Certificate caCert = Pem.readCertificates(Files.readString(out.resolve("ca.crt"))).get(0);
        X509Certificate server = Pem.readCertificates(Files.readString(out.resolve("server.crt"))).get(0);
        server.verify(caCert.getPublicKey());
        assertTrue(server.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"), "serverAuth");
        assertEquals(-1, server.getBasicConstraints(), "servidor nao e CA");
        var names = server.getSubjectAlternativeNames().stream().map(l -> l.get(1).toString()).toList();
        assertTrue(names.containsAll(List.of("atlas.atlas.svc", "10.99.0.1", "172.17.0.1")), names.toString());
        Pem.readPrivateKey(Files.readString(out.resolve("server.key")));

        for (String key : List.of("ca.key", "server.key")) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(out.resolve(key));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms, key);
        }
        // a pasta guarda a chave da CA: so o dono entra (a umask protege tambem a janela antes do chmod)
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(out));
        assertTrue(!log.contains("PRIVATE KEY"), "script nao imprime chave");
    }

    @Test
    void refusesToOverwriteAnExistingPki() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/openssl")) || Files.isExecutable(Path.of("/bin/openssl")), "sem openssl");
        Path out = Files.createTempDirectory("pki").resolve("atlas");
        assertEquals(0, run(out, "IP:10.99.0.1").exitValue());
        String before = Files.readString(out.resolve("ca.key"));
        assertNotEquals(0, run(out, "IP:10.99.0.1").exitValue());
        assertEquals(before, Files.readString(out.resolve("ca.key")), "CA existente nao muda");
    }

    @Test
    void needsAtLeastOneName() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/openssl")) || Files.isExecutable(Path.of("/bin/openssl")), "sem openssl");
        Path out = Files.createTempDirectory("pki").resolve("atlas");
        assertNotEquals(0, run(out).exitValue());
        assertTrue(!Files.exists(out.resolve("ca.key")));
    }
}
