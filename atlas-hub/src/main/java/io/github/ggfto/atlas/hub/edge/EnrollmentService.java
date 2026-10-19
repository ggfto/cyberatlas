package io.github.ggfto.atlas.hub.edge;

import io.github.ggfto.atlas.protocol.Csr;
import io.github.ggfto.atlas.protocol.Pem;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Edges e enrollment (molde do HiveKeeper, simplificado para um operador so). Token de uso unico valido 1 h,
 * guardado so como SHA-256; o edge gera a chave e manda o CSR; o hub assina com CN = edgeId que ele atribuiu.
 * So o certificado vigente de um edge nao revogado e aceito no handshake. Na F2 em memoria; Postgres na F5.
 */
public final class EnrollmentService {

    static final Duration TOKEN_TTL = Duration.ofHours(1);
    static final Duration CERT_TTL = Duration.ofDays(90);
    private static final Pattern MACHINE = Pattern.compile("[a-z0-9][a-z0-9-]{0,30}");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record NewEdge(String edgeId, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "NewEdge[" + edgeId + ", token omitido]";
        }
    }

    public record Issued(String edgeId, String certificatePem, String caPem) {
    }

    private record Edge(String edgeId, String machine, String fingerprint, Instant revokedAt) {
    }

    private record PendingToken(String edgeId, Instant expiresAt) {
    }

    private final AtlasCa ca;
    private final Supplier<Instant> clock;
    private final Map<String, Edge> edges = new ConcurrentHashMap<>();
    private final Map<String, PendingToken> tokens = new ConcurrentHashMap<>();

    public EnrollmentService(AtlasCa ca, Supplier<Instant> clock) {
        this.ca = ca;
        this.clock = clock;
    }

    public synchronized NewEdge createEdge(String machine) {
        if (machine == null || !MACHINE.matcher(machine).matches()) {
            throw new EnrollmentException("nome de maquina invalido");
        }
        String edgeId = "edge-" + machine;
        if (edges.containsKey(edgeId)) {
            throw new EnrollmentException("ja existe edge para a maquina " + machine + " (use re-enroll)");
        }
        edges.put(edgeId, new Edge(edgeId, machine, null, null));
        return mint(edgeId);
    }

    /** Novo token para o mesmo edge (troca de maquina, chave perdida). O cert atual continua ate o novo enrollment. */
    public synchronized NewEdge reEnroll(String edgeId) {
        Edge e = edges.get(edgeId);
        if (e == null) {
            throw new EnrollmentException("edge desconhecido");
        }
        edges.put(edgeId, new Edge(edgeId, e.machine(), e.fingerprint(), null));
        return mint(edgeId);
    }

    public synchronized Issued enroll(String token, String csrPem) {
        PendingToken p = tokens.remove(hash(token));
        if (p == null || !clock.get().isBefore(p.expiresAt())) {
            throw new EnrollmentException("token de enrollment invalido, vencido ou ja usado");
        }
        Edge e = edges.get(p.edgeId());
        try {
            X509Certificate cert = ca.issueClient(Csr.parse(csrPem), e.edgeId(), CERT_TTL);
            edges.put(e.edgeId(), new Edge(e.edgeId(), e.machine(), fingerprint(cert), null));
            return new Issued(e.edgeId(), Pem.certificate(cert), ca.certificatePem());
        } catch (EnrollmentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new EnrollmentException("CSR invalido");
        }
    }

    public void revoke(String edgeId) {
        edges.computeIfPresent(edgeId, (k, e) -> new Edge(k, e.machine(), e.fingerprint(), clock.get()));
    }

    /** A maquina do edge dono deste certificado, se ele for o vigente de um edge nao revogado. */
    public Optional<String> activeMachine(X509Certificate cert) {
        String cn = cert.getSubjectX500Principal().getName();
        if (!cn.startsWith("CN=")) {
            return Optional.empty();
        }
        Edge e = edges.get(cn.substring(3));
        if (e == null || e.revokedAt() != null || e.fingerprint() == null) {
            return Optional.empty();
        }
        try {
            return e.fingerprint().equals(fingerprint(cert)) ? Optional.of(e.machine()) : Optional.empty();
        } catch (CertificateEncodingException ex) {
            return Optional.empty();
        }
    }

    List<String> storedTokenHashes() {
        return List.copyOf(tokens.keySet());
    }

    @Override
    public String toString() {
        return "EnrollmentService[" + edges.size() + " edges, " + tokens.size() + " tokens pendentes]";
    }

    private NewEdge mint(String edgeId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = clock.get().plus(TOKEN_TTL);
        tokens.values().removeIf(t -> t.edgeId().equals(edgeId));
        tokens.put(hash(token), new PendingToken(edgeId, expires));
        return new NewEdge(edgeId, token, expires);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(token).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fingerprint(X509Certificate cert) throws CertificateEncodingException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String fingerprintOf(X509Certificate cert) throws GeneralSecurityException {
        return fingerprint(cert);
    }
}
