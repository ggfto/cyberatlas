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
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Layout manual por no (design §3.2), com versao: so grava se a rev esperada for a atual (0 = criar). A troca e um
 * UPDATE/INSERT condicional no proprio banco, entao dois PATCH concorrentes na mesma rev nunca vencem os dois.
 * So a API do operador escreve aqui; o reconciliador nao recebe esta classe.
 */
@ApplicationScoped
public class LayoutStore {

    @RegisterForReflection
    public record Position(String nodeKey, double x, double y, boolean pinned, long rev, String updatedBy, Instant updatedAt) {
    }

    public sealed interface Result {
        record Saved(Position position) implements Result {
        }

        /** Rev divergente; {@code current} e o valor atual (null se o no nao tem layout manual). */
        record Conflict(Position current) implements Result {
        }
    }

    private static final String COLS = "node_key, x, y, pinned, rev, updated_by, updated_at";

    private final DataSource db;
    private final Clock clock;

    @Inject
    LayoutStore(DataSource db) {
        this(db, Clock.systemUTC());
    }

    LayoutStore(DataSource db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public List<Position> all() {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("select " + COLS + " from layout_node order by node_key")) {
            return read(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("layout indisponivel", e);
        }
    }

    public Optional<Position> get(String key) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("select " + COLS + " from layout_node where node_key = ?")) {
            ps.setString(1, key);
            return read(ps).stream().findFirst();
        } catch (SQLException e) {
            throw new IllegalStateException("layout indisponivel", e);
        }
    }

    public Result put(String key, double x, double y, boolean pinned, long expectedRev, String who) {
        String sql = expectedRev == 0
                ? "insert into layout_node (x, y, pinned, updated_by, updated_at, node_key, rev) values (?, ?, ?, ?, ?, ?, 1)"
                        + " on conflict (node_key) do nothing returning " + COLS
                : "update layout_node set x = ?, y = ?, pinned = ?, updated_by = ?, updated_at = ?, rev = rev + 1"
                        + " where node_key = ? and rev = ? returning " + COLS;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, x);
            ps.setDouble(2, y);
            ps.setBoolean(3, pinned);
            ps.setString(4, who);
            ps.setTimestamp(5, Timestamp.from(clock.instant()));
            ps.setString(6, key);
            if (expectedRev != 0) {
                ps.setLong(7, expectedRev);
            }
            List<Position> saved = read(ps);
            return saved.isEmpty() ? new Result.Conflict(get(key).orElse(null)) : new Result.Saved(saved.get(0));
        } catch (SQLException e) {
            throw new IllegalStateException("layout indisponivel", e);
        }
    }

    /** Volta o no ao layout automatico, se a rev for a atual. */
    public boolean delete(String key, long expectedRev) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("delete from layout_node where node_key = ? and rev = ?")) {
            ps.setString(1, key);
            ps.setLong(2, expectedRev);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("layout indisponivel", e);
        }
    }

    private static List<Position> read(PreparedStatement ps) throws SQLException {
        List<Position> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Position(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getBoolean(4), rs.getLong(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant()));
            }
        }
        return out;
    }
}
