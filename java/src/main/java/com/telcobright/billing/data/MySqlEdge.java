package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.sql.SqlDialect;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The MySQL edge — exactly what {@link MySqlCdrBatchRunner} did before it had edges: the schema is the connection's
 * catalog (a tenant is a database), the batch lock is {@code GET_LOCK} / {@code RELEASE_LOCK}, the tables are
 * somebody else's (applied per tenant by hand), the key is {@code UniqueBillId} in {@code cdr}.
 */
public final class MySqlEdge implements DatasourceEdge {

    @Override
    public SqlDialect Dialect() {
        return SqlDialect.MySql;
    }

    @Override
    public String SchemaOf(Connection conn) {
        String schema;
        try {
            schema = conn.getCatalog();
        } catch (SQLException e) {
            schema = null;
        }
        return schema != null && !schema.isEmpty() ? schema : "default";
    }

    /** GET_LOCK is session-scoped (not transaction-scoped), so it stays held across the commit. */
    @Override
    public void AcquireBatchLock(Connection conn, String name) {
        try (var stmt = conn.prepareStatement("select get_lock(?, 30)")) {
            stmt.setString(1, name);
            try (var rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 1) return;
            }
        } catch (SQLException e) {
            throw new RuntimeException("acquiring tenant batch lock " + name + " failed", e);
        }
        throw new RuntimeException("tenant batch lock " + name + " not acquired within 30s (another batch still running?)");
    }

    @Override
    public void ReleaseBatchLock(Connection conn, String name) {
        try (var stmt = conn.prepareStatement("select release_lock(?)")) {
            stmt.setString(1, name);
            stmt.executeQuery();
        } catch (SQLException ignored) {
            // best-effort: closing the session releases the lock anyway.
        }
    }

    /** Nothing: on MySQL the tenant's tables are applied by hand, per schema (sql/summary_outbox.sql, the legacy cdr). */
    @Override
    public void PrepareSchema(Connection conn, String schema) {
    }

    @Override
    public IdempotencyKey Key() {
        return IdempotencyKey.UniqueBillId;
    }

    @Override
    public ISqlExecutor Executor(Connection conn) {
        return new MySqlExecutor(conn);
    }
}
