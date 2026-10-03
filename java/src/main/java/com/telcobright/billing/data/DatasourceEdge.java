package com.telcobright.billing.data;

import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.sql.SqlDialect;

import java.sql.Connection;

/**
 * What differs between the two write targets of a tenant's cdr batch — and nothing else (brief B6: "the same
 * pipeline and the same single transaction; what changes is the edge"). {@link MySqlCdrBatchRunner} owns the one
 * transaction and its order; it asks the edge for:
 *
 * <ul>
 *   <li>the tenant's <b>schema</b> of a connection (a MySQL database; a PostgreSQL schema of the switch database);</li>
 *   <li>the per-tenant <b>batch lock</b>, held across the commit;</li>
 *   <li>what the schema needs <b>before</b> it can take a batch (PostgreSQL: billing-core's own tables);</li>
 *   <li>the <b>idempotency key</b> of a record;</li>
 *   <li>the <b>executor</b> the pipeline's writers emit their SQL through (it names the dialect of the literals).</li>
 * </ul>
 */
public interface DatasourceEdge {

    SqlDialect Dialect();

    /** The tenant's schema this connection writes: the lock's name and the log's. Never null. */
    String SchemaOf(Connection conn);

    /** Take the tenant's batch lock, or throw after 30 s. It must stay held ACROSS the commit: that is what makes
     * the summary outbox ids commit-ordered and the max-id seed race-free. */
    void AcquireBatchLock(Connection conn, String lockName);

    /** Release it, after the commit or the rollback. Best effort: closing the session releases it too. */
    void ReleaseBatchLock(Connection conn, String lockName);

    /** Under the lock, before the batch's work: make the schema ready to take a batch. Commits what it makes. */
    void PrepareSchema(Connection conn, String schema);

    /** The batch of this schema did not commit: forget what was assumed about the schema, look again next time. */
    default void BatchFailed(String schema) {
    }

    IdempotencyKey Key();

    ISqlExecutor Executor(Connection conn);
}
