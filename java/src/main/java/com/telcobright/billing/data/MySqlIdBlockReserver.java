package com.telcobright.billing.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The MySQL {@link IIdBlockReserver}: reserves id blocks out of the legacy {@code autoincrementcounter}
 * table ({@code tableName varchar(200) PRIMARY KEY, value bigint not null}), which already exists in every
 * tenant schema and which the RETIRED legacy engine used for exactly this purpose.
 *
 * <p><b>Why the counter and not {@code max(id)}.</b> {@code acc_chargeable} is RANGE-COLUMNS partitioned on
 * {@code transactiontime} with ~2,368 partitions and has NO primary key and NO index on {@code id}, so
 * {@code select max(id) from acc_chargeable} is a full scan of every partition — measured at 3.68 s and 8.85 M
 * rows examined per call on the live PBX schema, which was 79% of every billing batch's wall time. A counter
 * row is a single primary-key lookup instead.</p>
 *
 * <p><b>Why {@code id} cannot simply be made UNIQUE.</b> MySQL requires every unique/primary key of a
 * partitioned table to contain the partitioning column, so {@code UNIQUE(id)} is impossible while the table is
 * partitioned on {@code transactiontime}. There is therefore NO database backstop against a duplicate id — the
 * allocator itself is the only guarantee, which is why reservation is one atomic statement.</p>
 *
 * <p><b>The atomic reservation.</b> {@code update ... set value = last_insert_id(value + n)} both advances the
 * counter and stores the new value in the session's {@code LAST_INSERT_ID()}, under the row's exclusive lock,
 * in ONE statement. Two concurrent reservers therefore serialise on the row and read back two DISJOINT blocks;
 * there is no window between reading and writing for anyone to squeeze into.</p>
 *
 * <p><b>Counter semantics: {@code value} is the highest id RESERVED, not the highest written.</b> This is the
 * legacy meaning, confirmed on the live schema: when the legacy engine stopped, its counter stood at 2,355,708
 * while the highest {@code acc_chargeable.id} it had actually written was 2,349,050 — 6,658 ids reserved and
 * never used. The counter is an UPPER bound on ids in use and must never be treated as a lower one.</p>
 *
 * <p>Runs on its OWN autocommit connection, never the caller's batch connection: a reservation must survive
 * the rollback of the batch that triggered it, and must not hold the counter row's lock for the length of a
 * batch transaction.</p>
 */
public final class MySqlIdBlockReserver implements IIdBlockReserver {

    /** The legacy counter table — already present in every tenant schema. */
    public static final String CounterTable = "autoincrementcounter";

    private final MySqlConnectionFactory _connections;
    private final String _schema;

    public MySqlIdBlockReserver(MySqlConnectionFactory connections, String schema) {
        _connections = connections;
        _schema = schema;
    }

    /** The schema this reserver allocates for — allocators must never be shared across tenants. */
    public String Schema() {
        return _schema;
    }

    @Override
    public long ReserveBlock(String table, int count) throws SQLException {
        if (count < 1) throw new IllegalArgumentException("block size must be >= 1, got " + count);
        try (Connection conn = _connections.Open(_schema)) {
            conn.setAutoCommit(true);   // the reservation is durable on its own, independent of any batch tx
            long newValue = Advance(conn, table, count);
            return newValue - count + 1;
        }
    }

    /** {@code value += count} atomically; returns the NEW value (the highest id now reserved). */
    private static long Advance(Connection conn, String table, int count) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "update " + CounterTable + " set value = last_insert_id(value + ?) where tableName = ?")) {
            ps.setInt(1, count);
            ps.setString(2, table);
            if (ps.executeUpdate() == 0)
                throw new SQLException("no " + CounterTable + " row for '" + table
                        + "' — SeedAtLeast must run before the first reservation");
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("select last_insert_id()")) {
            if (!rs.next()) throw new SQLException("last_insert_id() returned no row after reserving " + table);
            return rs.getLong(1);
        }
    }

    /**
     * Raise the counter to at least {@code floor}, creating the row if absent, and return the resulting value.
     * Called ONCE per process per schema with the table's live {@code max(id)} — the expensive scan happens
     * here, at startup, instead of in every batch.
     *
     * <p>{@code greatest()} is what makes this safe to run at any time, including while another instance is
     * mid-flight: the counter can only ever move UP, so a startup seed can never drag it below a block another
     * writer has already reserved but not yet written.</p>
     */
    public long SeedAtLeast(String table, long floor) throws SQLException {
        try (Connection conn = _connections.Open(_schema)) {
            conn.setAutoCommit(true);
            try (PreparedStatement ps = conn.prepareStatement(
                    "insert ignore into " + CounterTable + " (tableName, value) values (?, ?)")) {
                ps.setString(1, table);
                ps.setLong(2, floor);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "update " + CounterTable + " set value = greatest(value, ?) where tableName = ?")) {
                ps.setLong(1, floor);
                ps.setString(2, table);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "select value from " + CounterTable + " where tableName = ?")) {
                ps.setString(1, table);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("counter row for '" + table + "' vanished after seeding");
                    return rs.getLong(1);
                }
            }
        }
    }

    /** The one expensive read, run once per process per schema: the table's current highest id (0 if empty). */
    public long CurrentMaxId(String table, String idColumn) throws SQLException {
        try (Connection conn = _connections.Open(_schema);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select coalesce(max(" + idColumn + "), 0) from " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
