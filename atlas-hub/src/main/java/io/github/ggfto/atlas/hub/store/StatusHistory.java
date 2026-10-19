package io.github.ggfto.atlas.hub.store;

import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.TopologyNode;
import io.github.ggfto.atlas.hub.TopologyService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Historico minimo de estado (design §3.3): grava so transicoes (a primeira vez que um no aparece nao e mudanca,
 * nem sumir e voltar no mesmo estado), retencao de 7 dias. Le o mapa do reconciliador; nunca escreve nele.
 */
@ApplicationScoped
public class StatusHistory {

    private static final Logger LOG = Logger.getLogger(StatusHistory.class);
    public static final Duration RETENTION = Duration.ofDays(7);

    @RegisterForReflection
    public record Change(String nodeKey, String from, String to, String reason, Instant at) {
    }

    private final DataSource db;
    private final TopologyService topology;
    private final Clock clock;
    private final Map<String, State> last = new HashMap<>();

    @Inject
    StatusHistory(DataSource db, TopologyService topology) {
        this.db = db;
        this.topology = topology;
        this.clock = Clock.systemUTC();
    }

    @Scheduled(every = "{atlas.stream.every}")
    void tick() {
        observe(topology.snapshot().nodes());
    }

    @Scheduled(every = "1h")
    void scheduledPurge() {
        purge();
    }

    public synchronized void observe(List<TopologyNode> nodes) {
        Instant now = clock.instant();
        List<Change> changes = new ArrayList<>();
        for (TopologyNode n : nodes) {
            State before = last.put(n.node().key(), n.status().state());
            if (before != null && before != n.status().state()) {
                changes.add(new Change(n.node().key(), before.name(), n.status().state().name(), n.status().reason(), now));
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("insert into status_change (node_key, from_state, to_state, reason, at) values (?, ?, ?, ?, ?)")) {
            for (Change ch : changes) {
                ps.setString(1, ch.nodeKey());
                ps.setString(2, ch.from());
                ps.setString(3, ch.to());
                ps.setString(4, ch.reason());
                ps.setTimestamp(5, Timestamp.from(ch.at()));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOG.errorf("historico nao gravado (%d mudancas): %s", changes.size(), e.getMessage());
        }
    }

    /** Apaga o que passou da retencao; devolve quantas linhas sairam. */
    public int purge() {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("delete from status_change where at < ?")) {
            ps.setTimestamp(1, Timestamp.from(clock.instant().minus(RETENTION)));
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.errorf("limpeza do historico falhou: %s", e.getMessage());
            return 0;
        }
    }

    public List<Change> of(String key, int limit) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "select node_key, from_state, to_state, reason, at from status_change where node_key = ? order by at desc, id desc limit ?")) {
            ps.setString(1, key);
            ps.setInt(2, Math.max(1, Math.min(limit, 500)));
            List<Change> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Change(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("historico indisponivel", e);
        }
    }
}
