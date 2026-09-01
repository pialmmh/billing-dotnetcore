package com.telcobright.billing.data;

import com.telcobright.billing.mediation.cdr.Entry;
import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.summary.SummaryRollup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The transaction boundary for ONE summary roll-up sweep of a tenant schema — the CONSUMER-side twin of
 * {@link MySqlCdrBatchRunner}. In one transaction (held under a session named lock so two consumers never
 * double-fold and the {@code sum_voice} {@code max(id)} seeding is race-free): read the tenant's cursor from
 * {@code summary_offset}, SELECT the next page of {@code summary_affected} rows ({@code id > offset}), decode +
 * roll them up into the {@code sum_voice_*} tables via {@link SummaryRollup}, advance the cursor, commit.
 *
 * <p>All-or-nothing: the {@code sum_voice} writes and the advanced offset commit together or not at all — so a
 * crash re-folds the SAME page and never skips a row (the offset only moves on commit) and never double-counts
 * (the sum_voice writes rolled back with it). At-least-once with an atomic cursor = effectively once.</p>
 *
 * <p>{@link #EnsureOffsetTable} is separate and MUST be called OUTSIDE this transaction (a {@code CREATE TABLE}
 * implicitly commits in MySQL, which would split the sweep's atomic unit).</p>
 */
public final class MySqlSummaryBatchRunner {

    /** The outcome of one sweep: outbox rows consumed, the new cursor value, and customer-leg calls folded. */
    public record Result(int rowsConsumed, long newOffset, int callsFolded) {}

    /** Fold the next page (up to {@code maxRows}) of the tenant's {@code summary_affected} into sum_voice. */
    public Result Run(Connection conn, String entityType, int maxRows, int segmentSize) {
        try {
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        // Session-scoped lock held across the commit — same guarantees as the cdr batch runner: the sum_voice
        // max(id) seeding is race-free and no second consumer folds the same page concurrently.
        String lock = SummaryLockName(conn);
        AcquireLock(conn, lock);
        try {
            long offset = ReadOffset(conn, entityType);
            List<SummaryRollup.OutboxRow> rows = ReadRows(conn, entityType, offset, maxRows);
            if (rows.isEmpty()) {
                conn.commit();
                return new Result(0, offset, 0);
            }
            var ids = new MaxIdSeededAutoIncrementManager(conn);   // seeds each sum_voice table from its max(id)
            var store = new MySqlSummaryStore(conn);
            int folded = SummaryRollup.Apply(store, ids, rows, segmentSize);
            long newOffset = rows.get(rows.size() - 1).id();       // rows are ORDER BY id ASC
            WriteOffset(conn, entityType, newOffset);
            conn.commit();                                          // the ONE commit: sum_voice + cursor together
            return new Result(rows.size(), newOffset, folded);
        } catch (Throwable t) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                t.addSuppressed(re);
            }
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException(t);
        } finally {
            ReleaseLock(conn, lock);
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // restore best-effort; the connection is the caller's to close.
            }
        }
    }

    public Result Run(Connection conn, String entityType, int maxRows) {
        return Run(conn, entityType, maxRows, BatchSqlWriter.DefaultSegmentSize);
    }

    /** Create the per-schema consumer cursor table if absent. Call ONCE per tenant in autocommit mode — never
     * inside {@link #Run}'s transaction (DDL implicitly commits in MySQL). Idempotent. */
    public static void EnsureOffsetTable(Connection conn) {
        String ddl = "create table if not exists summary_offset ("
                + "entity_type varchar(32) not null, "
                + "last_offset bigint not null default 0, "
                + "updated_at timestamp not null default current_timestamp on update current_timestamp, "
                + "primary key (entity_type)) engine=InnoDB";
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<SummaryRollup.OutboxRow> ReadRows(Connection conn, String entityType, long offset, int maxRows) {
        String sql = "select id, op, data from summary_affected where entity_type=? and id>? order by id limit ?";
        List<SummaryRollup.OutboxRow> rows = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, entityType);
            st.setLong(2, offset);
            st.setInt(3, maxRows);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String op = rs.getString("op");
                    List<Entry> entries = SummaryOutboxWriter.Decode(rs.getString("data"));
                    rows.add(new SummaryRollup.OutboxRow(id, op, entries));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return rows;
    }

    private static long ReadOffset(Connection conn, String entityType) {
        try (PreparedStatement st = conn.prepareStatement(
                "select last_offset from summary_offset where entity_type=?")) {
            st.setString(1, entityType);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void WriteOffset(Connection conn, String entityType, long offset) {
        try (PreparedStatement st = conn.prepareStatement(
                "insert into summary_offset (entity_type, last_offset) values (?, ?) "
                        + "on duplicate key update last_offset=values(last_offset)")) {
            st.setString(1, entityType);
            st.setLong(2, offset);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ── named lock (session-scoped, so it stays held across the commit) — mirrors MySqlCdrBatchRunner ──
    private static String SummaryLockName(Connection conn) {
        String schema;
        try {
            schema = conn.getCatalog();
        } catch (SQLException e) {
            schema = null;
        }
        return "billing_summary_" + (schema != null && !schema.isEmpty() ? schema : "default");
    }

    private static void AcquireLock(Connection conn, String name) {
        try (PreparedStatement st = conn.prepareStatement("select get_lock(?, 30)")) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 1) return;
            }
        } catch (SQLException e) {
            throw new RuntimeException("acquiring summary lock " + name + " failed", e);
        }
        throw new RuntimeException("summary lock " + name + " not acquired within 30s (another consumer running?)");
    }

    private static void ReleaseLock(Connection conn, String name) {
        try (PreparedStatement st = conn.prepareStatement("select release_lock(?)")) {
            st.setString(1, name);
            st.executeQuery();
        } catch (SQLException ignored) {
            // best-effort: closing the session releases the lock anyway.
        }
    }
}
