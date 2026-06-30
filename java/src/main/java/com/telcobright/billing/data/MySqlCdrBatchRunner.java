package com.telcobright.billing.data;

import com.telcobright.billing.mediation.cdr.CdrBatch;
import com.telcobright.billing.mediation.cdr.CdrBatchResult;
import com.telcobright.billing.mediation.cdr.CdrPipeline;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.summary.cache.IAutoIncrementManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * The TOP-LEVEL transaction boundary for ONE tenant's cdr batch — the legacy CdrJobProcessor's
 * {@code set autocommit=0 … commit / rollback}, at the high-level entry. It owns the connection's SINGLE
 * transaction: begin -&gt; run the whole {@link CdrPipeline} pipeline (which only EMITS SQL through the
 * connection-bound {@link MySqlSummaryStore}; NO inner class/method commits or rolls back) -&gt; commit. On ANY
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
        try {
            // the pipeline writes EVERYTHING through this connection-bound store — one connection, one transaction.
            var store = new MySqlSummaryStore(conn);
            var batch = new CdrBatch(mediation, partners, cdrs, store, ids, segmentSize);
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
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // restore best-effort; the connection is the caller's to close.
            }
        }
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
