package io.github.ggfto.atlas.hub.store;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Auditoria (design §4): abertura de log, login break-glass, enrollment, revogacao. Falha ao gravar nao derruba a
 * acao (o hub e painel, nao base), mas vai para o log do processo com o mesmo conteudo.
 */
@ApplicationScoped
public class AuditLog {

    private static final Logger LOG = Logger.getLogger(AuditLog.class);

    @RegisterForReflection
    public record Entry(Instant at, String actor, String action, String target) {
    }

    private final DataSource db;
    private final Clock clock;

    @Inject
    AuditLog(DataSource db) {
        this(db, Clock.systemUTC());
    }

    AuditLog(DataSource db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public void record(String actor, String action, String target) {
        LOG.infof("auditoria: %s %s %s", actor, action, target);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("insert into audit (at, actor, action, target) values (?, ?, ?, ?)")) {
            ps.setTimestamp(1, Timestamp.from(clock.instant()));
            ps.setString(2, actor);
            ps.setString(3, action);
            ps.setString(4, target);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("auditoria nao gravada (%s %s %s): %s", actor, action, target, e.getMessage());
        }
    }

    public List<Entry> recent(int limit) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("select at, actor, action, target from audit order by at desc, id desc limit ?")) {
            ps.setInt(1, Math.max(1, Math.min(limit, 1000)));
            List<Entry> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("auditoria indisponivel", e);
        }
    }
}
