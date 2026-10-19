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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Edges e enrollment (molde do HiveKeeper, simplificado para um operador so). Token de uso unico valido 1 h,
 * guardado so como SHA-256; o edge gera a chave e manda o CSR; o hub assina com CN = edgeId que ele atribuiu.
 * So o certificado vigente de um edge nao revogado e aceito no handshake. Tudo no Postgres (V2__edges.sql): edges
 * e tokens sobrevivem ao restart do hub, e duas instancias no mesmo banco nao gastam o mesmo token duas vezes.
 */
public final class EnrollmentService {

    static final Duration TOKEN_TTL = Duration.ofHours(1);
    static final Duration CERT_TTL = Duration.ofDays(90);
    private static final Pattern MACHINE = Pattern.compile("[a-z0-9][a-z0-9-]{0,30}");

    public record NewEdge(String edgeId, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "NewEdge[" + edgeId + ", token omitido]";
        }
    }

    public record Issued(String edgeId, String certificatePem, String caPem) {
    }

    private final AtlasCa ca;
    private final DataSource db;
    private final Supplier<Instant> clock;
    // nunca static: no native image um SecureRandom criado no build ficaria com a semente gravada no binario
    private final SecureRandom random = new SecureRandom();

    public EnrollmentService(AtlasCa ca, DataSource db, Supplier<Instant> clock) {
        this.ca = ca;
        this.db = db;
        this.clock = clock;
    }

    public NewEdge createEdge(String machine) {
        if (machine == null || !MACHINE.matcher(machine).matches()) {
            throw new EnrollmentException("nome de maquina invalido");
        }
        String edgeId = "edge-" + machine;
        return inTx(c -> {
            Timestamp now = Timestamp.from(now());
            try (PreparedStatement ps = c.prepareStatement("insert into edge (id, machine, status, created_at, updated_at) "
                    + "values (?, ?, 'pending', ?, ?) on conflict do nothing")) {
                ps.setString(1, edgeId);
                ps.setString(2, machine);
                ps.setTimestamp(3, now);
                ps.setTimestamp(4, now);
                if (ps.executeUpdate() == 0) {
                    throw new EnrollmentException("ja existe edge para a maquina " + machine + " (use re-enroll)");
                }
            }
            return mint(c, edgeId);
        });
    }

    /** Novo token para o mesmo edge (troca de maquina, chave perdida). O cert atual continua ate o novo enrollment. */
    public NewEdge reEnroll(String edgeId) {
        return inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("update edge set revoked_at = null, updated_at = ?, "
                    + "status = case when cert_fingerprint is null then 'pending' else 'active' end where id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now()));
                ps.setString(2, edgeId);
                if (ps.executeUpdate() == 0) {
                    throw new EnrollmentException("edge desconhecido");
                }
            }
            return mint(c, edgeId);
        });
    }

    public Issued enroll(String token, String csrPem) {
        return inTx(c -> {
            // consumo atomico: so uma transacao acha a linha com used = false; a outra espera o lock e nao acha nada
            String edgeId;
            try (PreparedStatement ps = c.prepareStatement("update enrollment_token set used = true "
                    + "where token_sha256 = ? and used = false and expires_at > ? returning edge_id")) {
                ps.setString(1, hash(token));
                ps.setTimestamp(2, Timestamp.from(now()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new EnrollmentException("token de enrollment invalido, vencido ou ja usado");
                    }
                    edgeId = rs.getString(1);
                }
            }
            X509Certificate cert;
            String fp;
            String certPem;
            String caPem;
            try {
                cert = ca.issueClient(Csr.parse(csrPem), edgeId, CERT_TTL);
                fp = fingerprint(cert);
                certPem = Pem.certificate(cert);
                caPem = ca.certificatePem();
            } catch (EnrollmentException ex) {
                c.commit(); // token gasto mesmo com CSR ruim (como antes)
                throw ex;
            } catch (Exception ex) {
                c.commit();
                throw new EnrollmentException("CSR invalido");
            }
            try (PreparedStatement ps = c.prepareStatement("update edge set status = 'active', cert_fingerprint = ?, "
                    + "cert_serial = ?, revoked_at = null, updated_at = ? where id = ?")) {
                ps.setString(1, fp);
                ps.setString(2, cert.getSerialNumber().toString(16));
                ps.setTimestamp(3, Timestamp.from(now()));
                ps.setString(4, edgeId);
                ps.executeUpdate();
            }
            return new Issued(edgeId, certPem, caPem);
        });
    }

    public void revoke(String edgeId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("update edge set status = 'revoked', revoked_at = ?, updated_at = ? where id = ?")) {
            Timestamp now = Timestamp.from(now());
            ps.setTimestamp(1, now);
            ps.setTimestamp(2, now);
            ps.setString(3, edgeId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    /** A maquina do edge dono deste certificado, se ele for o vigente de um edge nao revogado. */
    public Optional<String> activeMachine(X509Certificate cert) {
        String cn = cert.getSubjectX500Principal().getName();
        if (!cn.startsWith("CN=")) {
            return Optional.empty();
        }
        String fp;
        try {
            fp = fingerprint(cert);
        } catch (CertificateEncodingException ex) {
            return Optional.empty();
        }
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("select machine from edge where id = ? and status = 'active' and cert_fingerprint = ?")) {
            ps.setString(1, cn.substring(3));
            ps.setString(2, fp);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    /** Apaga tokens vencidos (usados ou nao). Chamado de hora em hora pelo EdgeHub e a cada emissao. */
    public int purgeExpiredTokens() {
        try (Connection c = db.getConnection()) {
            return purgeExpired(c);
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    List<String> storedTokenHashes() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("select token_sha256 from enrollment_token");
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    @Override
    public String toString() {
        return "EnrollmentService[postgres]";
    }

    private Instant now() {
        return clock.get().truncatedTo(ChronoUnit.MICROS); // a precisao do timestamptz
    }

    private NewEdge mint(Connection c, String edgeId) throws SQLException {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = now();
        Instant expires = now.plus(TOKEN_TTL);
        purgeExpired(c);
        try (PreparedStatement ps = c.prepareStatement("delete from enrollment_token where edge_id = ? and used = false")) {
            ps.setString(1, edgeId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("insert into enrollment_token (token_sha256, edge_id, expires_at, used, created_at) values (?, ?, ?, false, ?)")) {
            ps.setString(1, hash(token));
            ps.setString(2, edgeId);
            ps.setTimestamp(3, Timestamp.from(expires));
            ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
        }
        return new NewEdge(edgeId, token, expires);
    }

    private int purgeExpired(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("delete from enrollment_token where expires_at <= ?")) {
            ps.setTimestamp(1, Timestamp.from(now()));
            return ps.executeUpdate();
        }
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    /** Uma transacao; EnrollmentException desfaz (exceto o que a propria unidade ja tiver comitado). */
    private <T> T inTx(Work<T> work) {
        try (Connection c = db.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T out = work.run(c);
                c.commit();
                return out;
            } catch (RuntimeException | SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw unavailable(e);
        }
    }

    private static IllegalStateException unavailable(SQLException e) {
        return new IllegalStateException("banco de edges indisponivel", e);
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
