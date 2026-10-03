package com.telcobright.billing.mediation.sql;

/**
 * Runs one SQL statement on the single shared MySqlConnection (and its transaction) and returns the
 * affected-row count — the thin replacement for the legacy {@code DbCommand} + {@code DbWriterWithAccurateCount}
 * stored proc. Batching is layered on top by {@link BatchSqlWriter} (the ported
 * {@code CollectionSegmenter}). The live implementation wraps one {@code MySqlCommand} on the per-call
 * connection; tests use an in-memory capture.
 */
public interface ISqlExecutor {
    int ExecuteNonQuery(String sql);

    /** The engine this executor's connection speaks: the writers shape their literals (and the {@code cdr} row's
     * columns) for it. MySQL unless the executor says otherwise — every executor before PostgreSQL is MySQL's. */
    default SqlDialect Dialect() {
        return SqlDialect.MySql;
    }
}
