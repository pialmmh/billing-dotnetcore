package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.sql.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The PostgreSQL edge. A tenant is a SCHEMA of the one switch database (ad-is-a-call §3), so:
 *
 * <ul>
 *   <li><b>the schema</b> is the connection's current schema — never its catalog: the catalog is the DATABASE, the
 *       same for every tenant, and a lock named after it would queue every tier behind one lock;</li>
 *   <li><b>the batch lock</b> is a session-level advisory lock ({@code pg_advisory_lock}) on a key made from the
 *       lock's name. Session level, not transaction level: it must outlive the commit, as MySQL's {@code GET_LOCK}
 *       does. It is taken BEFORE anything is read or inserted and released after the commit, so the outbox ids of
 *       a schema become visible in commit order. A wait of 30 s is the limit, as on MySQL;</li>
 *   <li><b>the tables</b> are billing-core's own to make ({@link PostgresTenantTables}), the first time it serves
 *       the schema;</li>
 *   <li><b>the key</b> is the ratified one: {@code ChannelCallUuid}, in {@code cdr} and {@code cdrerror}.</li>
 * </ul>
 */
public final class PostgresEdge implements DatasourceEdge {
    private static final int LockWaitSeconds = 30;

    private final PostgresTenantTables _tables;
    private final String _lockWait;

    public PostgresEdge(PostgresTenantTables tables) {
        this(tables, LockWaitSeconds);
    }

    /** With another wait for the batch lock than the 30 s of a deployment (a test's). */
    PostgresEdge(PostgresTenantTables tables, int lockWaitSeconds) {
        _tables = tables;
        _lockWait = lockWaitSeconds + "s";
    }

    @Override
    public SqlDialect Dialect() {
        return SqlDialect.PostgreSql;
    }

    @Override
    public String SchemaOf(Connection conn) {
        try {
            String schema = conn.getSchema();
            if (schema == null || schema.isEmpty())
                throw new IllegalStateException("the connection has no current schema: the tenant's schema does not exist"
                        + " in this database, or this role may not use it");
            return schema;
        } catch (SQLException e) {
            throw new RuntimeException("the connection's schema could not be read", e);
        }
    }

    /** {@code lock_timeout} bounds the wait (it covers an advisory lock as any other); it is set for this one
     * statement and put back. A lock that is not obtained leaves the connection clean: the failed statement's
     * transaction is rolled back, and with it the timeout. */
    @Override
    public void AcquireBatchLock(Connection conn, String name) {
        try (Statement st = conn.createStatement()) {
            st.execute("set lock_timeout = '" + _lockWait + "'");
            try (PreparedStatement lock = conn.prepareStatement("select pg_advisory_lock(?)")) {
                lock.setLong(1, LockKeyOf(name));
                lock.execute();
            }
            st.execute("reset lock_timeout");
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) { /* the lock's failure is the one to report */ }
            throw new RuntimeException("tenant batch lock " + name + " not acquired within " + _lockWait
                    + " (another batch still running?)", e);
        }
    }

    @Override
    public void ReleaseBatchLock(Connection conn, String name) {
        try (PreparedStatement unlock = conn.prepareStatement("select pg_advisory_unlock(?)")) {
            unlock.setLong(1, LockKeyOf(name));
            unlock.execute();
        } catch (SQLException ignored) {
            // best-effort: closing the session releases the lock anyway.
        }
    }

    /** An advisory lock is a 64-bit number: the first eight bytes of the SHA-256 of the lock's name. Every
     * billing-core process makes the same number from the same name; two tenants never share one in practice. */
    static long LockKeyOf(String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void PrepareSchema(Connection conn, String schema) {
        _tables.Ensure(conn, schema);
    }

    @Override
    public void BatchFailed(String schema) {
        _tables.Forget(schema);
    }

    @Override
    public IdempotencyKey Key() {
        return IdempotencyKey.ChannelCallUuid;
    }

    @Override
    public ISqlExecutor Executor(Connection conn) {
        return new PostgresExecutor(conn);
    }
}
