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
import java.util.Set;

/**
 * The TOP-LEVEL transaction boundary for ONE tenant's cdr batch — the legacy CdrJobProcessor's
 * {@code set autocommit=0 … commit / rollback}, at the high-level entry. It owns the connection's SINGLE
 * transaction: begin -&gt; run the whole {@link CdrPipeline} pipeline (which only EMITS SQL through the
 * connection-bound executor; NO inner class/method commits or rolls back) -&gt; commit. On ANY
 * exception the WHOLE batch rolls back. All-or-nothing: cdr + cdrerror + chargeables + the summary outbox row persist
 * together or not at all.
 *
 * <p>The caller owns the per-call connection (the architect's single-connection rule); this owns the one
 * transaction around the batch.</p>
 *
 * <p><b>One runner, two engines.</b> The transaction, its order and the pipeline are the same on MySQL and on
 * PostgreSQL; what differs is the {@link DatasourceEdge} (the schema's name, the batch lock, the tables, the
 * idempotency key, the literals). {@link #Default()} is MySQL's, as it always was; {@link #On(DatasourceEdge)}
 * takes the edge of the profile's datasource. (The class keeps its name from the days it had one engine.)</p>
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
    private final DatasourceEdge _edge;

    public MySqlCdrBatchRunner(CdrPipeline processor) {
        this(processor, new MySqlEdge());
    }

    public MySqlCdrBatchRunner(CdrPipeline processor, DatasourceEdge edge) {
        _processor = processor;
        _edge = edge;
    }

    /** The MySQL runner — every deployment before PostgreSQL, and every existing caller. */
    public static MySqlCdrBatchRunner Default() {
        return new MySqlCdrBatchRunner(CdrPipeline.Default());
    }

    /** The runner of the datasource whose edge this is (the profile's {@code billing.datasource.kind}). */
    public static MySqlCdrBatchRunner On(DatasourceEdge edge) {
        return new MySqlCdrBatchRunner(CdrPipeline.Default(), edge);
    }

    /** The engine this runner writes for. */
    public com.telcobright.billing.mediation.sql.SqlDialect Dialect() {
        return _edge.Dialect();
    }

    public CdrBatchResult Run(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs,
            IAutoIncrementManager ids, int segmentSize) {
        return RunInternal(conn, mediation, partners, cdrs, ids, segmentSize, null, false);
    }

    /**
     * Same as {@link #Run(Connection, MediationContext, Map, List, IAutoIncrementManager, int)} but with the
     * explicit CUTOVER {@code legacyDedup} switch: when {@code true}, a cdr whose {@code SequenceNumber} is
     * already OWNED by legacy (present in this tenant's {@code cdr} OR {@code cdrerror}) is dropped BEFORE
     * billing (see {@link #FilterLegacyOwned}). {@code false} = the unchanged normal path.
     */
    public CdrBatchResult Run(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs,
            IAutoIncrementManager ids, int segmentSize, boolean legacyDedup) {
        return RunInternal(conn, mediation, partners, cdrs, ids, segmentSize, null, legacyDedup);
    }

    /** An action run INSIDE the batch transaction (under the tenant lock), before the pipeline. */
    @FunctionalInterface
    private interface InTxAction { void run(Connection conn) throws SQLException; }

    /** Cutover seq-ownership lookup seam — returns the subset of {@code candidates} legacy already owns. */
    @FunctionalInterface
    interface SeqOwnershipLookup { java.util.Set<Long> ownedSeqs(java.util.Set<Long> candidates) throws SQLException; }

    /**
     * Re-rate cdrs that were read back from {@code cdrerror}, atomically moving the ones that now rate into
     * {@code cdr}. The source {@code cdrerror} rows (by {@code IdCall}) are DELETED inside the SAME transaction
     * as the re-write, so the transition is all-or-nothing: either {@code cdrerror -> cdr + chargeable +
     * summary} commits, or every source row stays put in {@code cdrerror} (rollback). Idempotent — a cdr whose
     * key is already in the {@code cdr} table is dropped by the idempotency (the unique index is the hard
     * backstop), so re-running never double-bills; a call still failing is simply re-written to {@code cdrerror}
     * with its fresh reason. (The source rows are deleted BEFORE the idempotency reads the tables, so where the
     * key is also looked for in {@code cdrerror} the rows being reprocessed are not found there.)
     */
    public CdrBatchResult RunReprocess(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs, List<Long> sourceIdCalls,
            IAutoIncrementManager ids, int segmentSize) {
        // Reprocess is a distinct, operator-driven flow — never apply the cutover legacy-dedup here.
        return RunInternal(conn, mediation, partners, cdrs, ids, segmentSize, c -> {
            for (cdr x : cdrs) x.ErrorCode = null;          // clear the stale error so the row can re-rate clean
            DeleteCdrErrorsByIdCall(c, sourceIdCalls);       // remove the source error rows — atomic with the re-write
        }, false);
    }

    /**
     * ONE batch at a time per tenant schema. The lock is held across the whole batch INCLUDING the commit, which
     * gives two guarantees the pipeline relies on:
     * (a) summary_affected outbox ids become COMMIT-ordered, so the summary-service's "id &gt; offset" cursor can
     *     never skip a row that commits late out of order;
     * (b) the max(id) seeding of MaxIdSeededAutoIncrementManager is race-free.
     * (The legacy equivalent was the single job runner per tenant.)
     */
    private CdrBatchResult RunInternal(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs,
            IAutoIncrementManager ids, int segmentSize, InTxAction preProcess, boolean legacyDedup) {
        BeginTransaction(conn);
        String schema = _edge.SchemaOf(conn);
        String batchLock = "billing_batch_" + schema;
        _edge.AcquireBatchLock(conn, batchLock);
        try {
            _edge.PrepareSchema(conn, schema);
            if (ids == null) ids = new MaxIdSeededAutoIncrementManager(conn);
            // Reprocess-only: delete the source cdrerror rows here, INSIDE the tx and under the lock, so the
            // move to cdr is atomic (rollback restores them). No-op for the normal ingest path (null action).
            if (preProcess != null) preProcess.run(conn);
            List<cdr> toProcess = DropWhatMustNotBeBilledAgain(conn, cdrs, legacyDedup, batchLock);
            // the pipeline writes EVERYTHING through this connection-bound store — one connection, one transaction.
            var batch = new CdrBatch(mediation, partners, toProcess, _edge.Executor(conn), ids, segmentSize);
            var result = _processor.Process(batch);
            conn.commit();        // the ONE commit for the batch
            return result;
        } catch (Throwable t) {
            Rollback(conn, t);    // the ONE rollback — undo the whole batch
            _edge.BatchFailed(schema);
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException(t);   // wrap checked (e.g. SQLException from commit)
        } finally {
            _edge.ReleaseBatchLock(conn, batchLock);    // after commit/rollback — the lock covers the commit
            RestoreAutoCommit(conn);
        }
    }

    private static void BeginTransaction(Connection conn) {
        try {
            conn.setAutoCommit(false);   // conn.BeginTransaction()
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void Rollback(Connection conn, Throwable cause) {
        try {
            conn.rollback();
        } catch (SQLException re) {
            cause.addSuppressed(re);
        }
    }

    private static void RestoreAutoCommit(Connection conn) {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // restore best-effort; the connection is the caller's to close.
        }
    }

    /**
     * The three filters a batch passes before mediation, each under the tenant lock (so its reads see the true
     * committed state and no other batch can write between the check and our insert):
     * <ol>
     * <li><b>CUTOVER legacy-ownership dedup</b> (feature-gated; OFF by default): during the legacy→new cutover a
     *     cdr whose SequenceNumber is already owned by legacy (present in THIS tenant's cdr OR cdrerror) is
     *     dropped — legacy cdr = billed, legacy cdrerror = failed-and-NOT-recovered; either way NEW billing must
     *     not touch it. Batched (one query per table) and FAIL-SAFE (a lookup SQLException propagates → the whole
     *     batch rolls back / retries → never a silent bill).</li>
     * <li><b>A later copy of a record in the SAME batch</b> is dropped: one poll can deliver one call twice (a
     *     producer's retry, a republish after its restart). The first copy wins. Without this the pipeline's own
     *     duplicate guard aborts the batch, the ingest rewinds, and the same poll fails again — for ever.</li>
     * <li><b>Cross-batch idempotency (T3)</b>: a record whose key is ALREADY written in this schema is dropped. A
     *     redelivered Kafka poll-batch (offsets commit only after the DB commit — at-least-once) therefore cannot
     *     double-write / double-bill. The unique index on the key is the hard backstop if two processes ever race
     *     past this.</li>
     * </ol>
     * Which column is the key and which tables are read is the edge's ({@link IdempotencyKey}).
     */
    private List<cdr> DropWhatMustNotBeBilledAgain(Connection conn, List<cdr> cdrs, boolean legacyDedup,
            String batchLock) throws SQLException {
        List<cdr> afterLegacy = legacyDedup ? FilterLegacyOwned(cdrs, seqs -> jdbcOwnedSeqs(conn, seqs)) : cdrs;
        if (afterLegacy.size() < cdrs.size())
            log.infof("cutover legacy-dedup: skipped %d cdr(s) owned by legacy (seq in cdr/cdrerror) in %s",
                    cdrs.size() - afterLegacy.size(), batchLock);

        IdempotencyKey key = _edge.Key();
        for (cdr c : afterLegacy) key.StampOn(c);
        List<cdr> firstCopies = DropLaterCopiesInTheBatch(afterLegacy, key);
        if (firstCopies.size() < afterLegacy.size())
            log.infof("idempotency: dropped %d later cop(ies) of a record inside one batch in %s",
                    afterLegacy.size() - firstCopies.size(), batchLock);

        List<cdr> toProcess = DropWhatIsAlreadyWritten(conn, firstCopies, key);
        if (toProcess.size() < firstCopies.size())
            log.infof("idempotency: skipped %d already-written cdr(s) in %s (redelivery)",
                    firstCopies.size() - toProcess.size(), batchLock);
        return toProcess;
    }

    /** PURE: keep the FIRST record of each key, in order; a record with no key cannot be told from another and is
     * always kept. */
    static List<cdr> DropLaterCopiesInTheBatch(List<cdr> cdrs, IdempotencyKey key) {
        Set<String> seen = new HashSet<>();
        List<cdr> firstCopies = new ArrayList<>(cdrs.size());
        for (cdr c : cdrs) {
            String k = key.Of(c);
            if (IdempotencyKey.IsBlank(k) || seen.add(k)) firstCopies.add(c);
        }
        return firstCopies.size() == cdrs.size() ? cdrs : firstCopies;
    }

    /**
     * Cross-batch dedup: return the cdrs whose key is NOT already present in this schema, in any of the key's tables
     * (already-written rows are dropped). Called under the tenant batch lock, so the read is race-free against
     * other batches on the same schema. Cdrs with a null/empty key are always kept — they cannot be deduped, so
     * the producer must supply the key for at-least-once safety.
     */
    private static List<cdr> DropWhatIsAlreadyWritten(Connection conn, List<cdr> cdrs, IdempotencyKey key) {
        var candidates = new LinkedHashSet<String>();
        for (var c : cdrs)
            if (!IdempotencyKey.IsBlank(key.Of(c))) candidates.add(key.Of(c));
        if (candidates.isEmpty()) return cdrs;

        var already = new HashSet<String>();
        for (String table : key.Tables()) SelectExistingKeys(conn, table, key.Column(), candidates, already);
        if (already.isEmpty()) return cdrs;

        var kept = new ArrayList<cdr>(cdrs.size());
        for (var c : cdrs)
            if (IdempotencyKey.IsBlank(key.Of(c)) || !already.contains(key.Of(c))) kept.add(c);
        return kept;
    }

    /** Batched {@code SELECT <key> FROM <table> WHERE <key> IN (…)} (chunked), index-served. Table and column are
     * internal literals of the {@link IdempotencyKey}, never external input. */
    private static void SelectExistingKeys(Connection conn, String table, String column, Set<String> keys, Set<String> into) {
        var ids = new ArrayList<>(keys);
        final int chunk = 500;   // batches are small; keep the IN-list bounded
        for (int i = 0; i < ids.size(); i += chunk) {
            var slice = ids.subList(i, Math.min(i + chunk, ids.size()));
            var sql = new StringBuilder("select ").append(column).append(" from ").append(table)
                    .append(" where ").append(column).append(" in (");
            for (int j = 0; j < slice.size(); j++) sql.append(j == 0 ? "?" : ",?");
            sql.append(")");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int j = 0; j < slice.size(); j++) ps.setString(j + 1, slice.get(j));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) into.add(rs.getString(1));
                }
            } catch (SQLException e) {
                throw new RuntimeException("idempotency dedup query failed", e);
            }
        }
    }

    /**
     * CUTOVER legacy-ownership filter (only invoked when the {@code legacyDedup} flag is on). Keeps ONLY the
     * cdrs whose {@code SequenceNumber} is present in NEITHER legacy {@code cdr} nor legacy {@code cdrerror}
     * (i.e. legacy never owned them). SequenceNumber is the source-assigned identity shared by the legacy-file
     * and Kafka paths, so a legacy-billed call (seq in {@code cdr}) or a legacy-failed call (seq in
     * {@code cdrerror}, deliberately NOT recovered) is dropped here — absolute double-bill prevention.
     *
     * <p>The lookup is injected ({@link SeqOwnershipLookup}) so the full decision + the FAIL-SAFE contract are
     * unit-testable without a DB; production supplies {@link #jdbcOwnedSeqs}. The lookup is BATCHED (one query
     * per table for the whole poll-batch), and any {@code SQLException} it throws PROPAGATES — the caller's tx
     * rolls back and the poll-batch is retried, so a lookup failure NEVER results in a silent bill.</p>
     */
    static List<cdr> FilterLegacyOwned(List<cdr> cdrs, SeqOwnershipLookup lookup) throws SQLException {
        var seqs = new LinkedHashSet<Long>();
        for (cdr c : cdrs) if (c.SequenceNumber > 0) seqs.add(c.SequenceNumber);
        if (seqs.isEmpty()) return cdrs;                 // nothing checkable → normal path (seq is guaranteed by the preprocessor)
        java.util.Set<Long> owned = lookup.ownedSeqs(seqs);   // FAIL-SAFE: a throw here aborts the batch (no bill)
        return PartitionUnowned(cdrs, owned);
    }

    /** PURE: keep the cdrs whose SequenceNumber is NOT legacy-owned (seq &le; 0 is kept — cannot be matched). */
    static List<cdr> PartitionUnowned(List<cdr> cdrs, java.util.Set<Long> ownedSeqs) {
        if (ownedSeqs == null || ownedSeqs.isEmpty()) return cdrs;
        var kept = new ArrayList<cdr>(cdrs.size());
        for (cdr c : cdrs)
            if (c.SequenceNumber <= 0 || !ownedSeqs.contains(c.SequenceNumber)) kept.add(c);
        return kept;
    }

    /** Production seq-ownership lookup: the batched SequenceNumber IN-check against legacy cdr + cdrerror.
     *  (Package-visible so the read-only production rehearsal can invoke the EXACT production lookup path.) */
    static java.util.Set<Long> jdbcOwnedSeqs(Connection conn, java.util.Set<Long> candidates) throws SQLException {
        var owned = new HashSet<Long>();
        SelectExistingSeqs(conn, "cdr", candidates, owned);
        SelectExistingSeqs(conn, "cdrerror", candidates, owned);
        return owned;
    }

    /** Batched {@code SELECT SequenceNumber FROM <table> WHERE SequenceNumber IN (…)} (chunked), index-served. */
    private static void SelectExistingSeqs(Connection conn, String table, java.util.Set<Long> seqs,
            java.util.Set<Long> into) throws SQLException {
        var ids = new ArrayList<>(seqs);
        final int chunk = 500;   // bound the IN-list; the poll-batch is small, this just caps a pathological one
        for (int i = 0; i < ids.size(); i += chunk) {
            var slice = ids.subList(i, Math.min(i + chunk, ids.size()));
            var sql = new StringBuilder("select SequenceNumber from ").append(table).append(" where SequenceNumber in (");
            for (int j = 0; j < slice.size(); j++) sql.append(j == 0 ? "?" : ",?");
            sql.append(")");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int j = 0; j < slice.size(); j++) ps.setLong(j + 1, slice.get(j));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) into.add(rs.getLong(1));
                }
            }
        }
    }

    /** Delete the given source cdrerror rows by IdCall (the per-call identity), chunked. Runs inside the tx. */
    private static void DeleteCdrErrorsByIdCall(Connection conn, List<Long> idCalls) throws SQLException {
        if (idCalls == null || idCalls.isEmpty()) return;
        final int chunk = 500;
        for (int i = 0; i < idCalls.size(); i += chunk) {
            var slice = idCalls.subList(i, Math.min(i + chunk, idCalls.size()));
            var sql = new StringBuilder("delete from cdrerror where IdCall in (");
            for (int j = 0; j < slice.size(); j++) sql.append(j == 0 ? "?" : ",?");
            sql.append(")");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int j = 0; j < slice.size(); j++) ps.setLong(j + 1, slice.get(j));
                ps.executeUpdate();
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

    /** The ingest call-site overload: default ids/segment, with the explicit cutover {@code legacyDedup} switch. */
    public CdrBatchResult Run(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs, boolean legacyDedup) {
        return Run(conn, mediation, partners, cdrs, null, BatchSqlWriter.DefaultSegmentSize, legacyDedup);
    }
}
