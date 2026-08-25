package com.telcobright.billing.data;

import com.telcobright.billing.mediation.cdr.CdrBatch;
import com.telcobright.billing.mediation.cdr.CdrBatchResult;
import com.telcobright.billing.mediation.cdr.CdrPipeline;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.sql.IAutoIncrementManager;

import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The TOP-LEVEL transaction boundary for ONE tenant's cdr batch — the legacy CdrJobProcessor's
 * {@code set autocommit=0 … commit / rollback}, at the high-level entry. It owns the connection's SINGLE
 * transaction: begin -&gt; run the whole {@link CdrPipeline} pipeline (which only EMITS SQL through the
 * connection-bound {@link MySqlExecutor}; NO inner class/method commits or rolls back) -&gt; commit. On ANY
 * exception the WHOLE batch rolls back. All-or-nothing: cdr + cdrerror + chargeables + summaries persist
 * together or not at all.
 *
 * <p>The caller owns the per-call connection (the architect's single-MySqlConnection rule); this owns the one
 * transaction around the batch. The future job-fetch layer hands the decoded cdrs here.</p>
 *
 * <p>FAITHFUL-PORT NOTE (MySqlConnector -&gt; JDBC): there is no {@code MySqlTransaction} object. The legacy
 * {@code conn.BeginTransaction()} becomes {@code conn.setAutoCommit(false)}, {@code tx.Commit()} becomes
 * {@code conn.commit()}, {@code tx.Rollback()} becomes {@code conn.rollback()}; auto-commit is restored in a
 * {@code finally}. The bare C# {@code catch { rollback; throw; }} becomes a {@code catch (Throwable)} that
 * rolls back and rethrows (checked {@code SQLException} from commit is rewrapped as an unchecked
 * {@code RuntimeException}, since the C# method declared no checked exceptions).</p>
 */
public final class MySqlCdrBatchRunner {
    private static final Logger log = Logger.getLogger(MySqlCdrBatchRunner.class);

    private final CdrPipeline _processor;

    public MySqlCdrBatchRunner(CdrPipeline processor) {
        _processor = processor;
    }

    public static MySqlCdrBatchRunner Default() {
        return new MySqlCdrBatchRunner(CdrPipeline.Default());
    }

    public CdrBatchResult Run(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs,
            IAutoIncrementManager ids, int segmentSize) {
        try {
            conn.setAutoCommit(false);   // conn.BeginTransaction()
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        // ONE batch at a time per tenant schema. The named lock is held across the whole
        // batch INCLUDING the commit, which gives two guarantees the pipeline relies on:
        // (a) summary_affected outbox ids become COMMIT-ordered, so the summary-service's
        //     "id > offset" cursor can never skip a row that commits late out of order;
        // (b) the max(id) seeding of MaxIdSeededAutoIncrementManager is race-free.
        // (The legacy equivalent was the single job runner per tenant.)
        String batchLock = TenantBatchLockName(conn);
        AcquireLock(conn, batchLock);
        try {
            if (ids == null) ids = new MaxIdSeededAutoIncrementManager(conn);
            // Cross-batch idempotency (T3): under the per-schema lock (so this SELECT sees the true committed
            // state and no concurrent batch can write between the check and our insert), drop any cdr whose
            // UniqueBillId is ALREADY billed in this schema's cdr table. A redelivered Kafka poll-batch (offsets
            // commit only after the DB commit — at-least-once) therefore cannot double-write / double-bill.
            // The unique index on cdr(UniqueBillId) is the hard backstop if two processes ever race past this.
            List<cdr> toProcess = FilterAlreadyBilled(conn, cdrs);
            int skipped = cdrs.size() - toProcess.size();
            if (skipped > 0)
                log.infof("idempotency: skipped %d already-billed cdr(s) in %s (redelivery)", skipped, batchLock);
            // the pipeline writes EVERYTHING through this connection-bound store — one connection, one transaction.
            var store = new MySqlExecutor(conn);
            var batch = new CdrBatch(mediation, partners, toProcess, store, ids, segmentSize);
            var result = _processor.Process(batch);
            conn.commit();        // the ONE commit for the batch
            return result;
        } catch (Throwable t) {
            try {
                conn.rollback();  // the ONE rollback — undo the whole batch
            } catch (SQLException re) {
                t.addSuppressed(re);
            }
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException(t);   // wrap checked (e.g. SQLException from commit)
        } finally {
            ReleaseLock(conn, batchLock);    // after commit/rollback — the lock covers the commit
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // restore best-effort; the connection is the caller's to close.
            }
        }
    }

    private static String TenantBatchLockName(Connection conn) {
        String schema;
        try {
            schema = conn.getCatalog();
        } catch (SQLException e) {
            schema = null;
        }
        return "billing_batch_" + (schema != null && !schema.isEmpty() ? schema : "default");
    }

    /** GET_LOCK is session-scoped (not transaction-scoped), so it stays held across the commit. */
    private static void AcquireLock(Connection conn, String name) {
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

    private static void ReleaseLock(Connection conn, String name) {
        try (var stmt = conn.prepareStatement("select release_lock(?)")) {
            stmt.setString(1, name);
            stmt.executeQuery();
        } catch (SQLException ignored) {
            // best-effort: closing the session releases the lock anyway.
        }
    }

    /**
     * Cross-batch dedup: return the cdrs whose UniqueBillId is NOT already present in this schema's cdr table
     * (already-billed rows are dropped). Called under the tenant batch lock, so the read is race-free against
     * other batches on the same schema. Cdrs with a null/empty UniqueBillId are always kept — they cannot be
     * deduped, so the producer must supply the key for at-least-once safety. Only the {@code cdr} table (final
     * bills) is checked; a previously-errored cdr may still be reprocessed (it might succeed after a config fix).
     */
    private static List<cdr> FilterAlreadyBilled(Connection conn, List<cdr> cdrs) {
        var candidates = new LinkedHashSet<String>();
        for (var c : cdrs)
            if (c.UniqueBillId != null && !c.UniqueBillId.isEmpty()) candidates.add(c.UniqueBillId);
        if (candidates.isEmpty()) return cdrs;

        var already = new HashSet<String>();
        var ids = new ArrayList<>(candidates);
        final int chunk = 500;   // batches are small; keep the IN-list bounded
        for (int i = 0; i < ids.size(); i += chunk) {
            var slice = ids.subList(i, Math.min(i + chunk, ids.size()));
            var sql = new StringBuilder("select UniqueBillId from cdr where UniqueBillId in (");
            for (int j = 0; j < slice.size(); j++) sql.append(j == 0 ? "?" : ",?");
            sql.append(")");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int j = 0; j < slice.size(); j++) ps.setString(j + 1, slice.get(j));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) already.add(rs.getString(1));
                }
            } catch (SQLException e) {
                throw new RuntimeException("idempotency dedup query failed", e);
            }
        }
        if (already.isEmpty()) return cdrs;

        var kept = new ArrayList<cdr>(cdrs.size());
        for (var c : cdrs)
            if (c.UniqueBillId == null || c.UniqueBillId.isEmpty() || !already.contains(c.UniqueBillId))
                kept.add(c);
        return kept;
    }

    // Default-parameter overloads — the C# method signed `ids = null, segmentSize = DefaultSegmentSize`.
    // Java has no default args, so each shorter call site gets an overload that fills the trailing defaults
    // and delegates to the canonical method (mirrors CdrBatch's port).
    public CdrBatchResult Run(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs) {
        return Run(conn, mediation, partners, cdrs, null, BatchSqlWriter.DefaultSegmentSize);
    }

    public CdrBatchResult Run(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs, IAutoIncrementManager ids) {
        return Run(conn, mediation, partners, cdrs, ids, BatchSqlWriter.DefaultSegmentSize);
    }
}
