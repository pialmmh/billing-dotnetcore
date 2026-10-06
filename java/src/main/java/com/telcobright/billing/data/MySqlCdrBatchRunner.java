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

    /**
     * The OUTGOING-SMS batch (Phase 1): same single transaction + per-schema lock as voice, but
     * <ul>
     * <li>the pipeline is {@link CdrPipeline#SmsOutgoing}, built for THIS batch over THIS connection (the tenant's
     *   billing-rule catalog is read inside the tx; account/ledger rows are locked through it);</li>
     * <li>ids MUST come from the shared counter ({@code ids} non-null — see {@link SmsIdAllocators#For});</li>
     * <li>dedup is {@link #FilterSmsSeen}: a redelivered SMS (its UniqueBillId already in cdr OR cdrerror) and, with
     *   {@code legacyDedup}, an SMS legacy already owns (its SequenceNumber in cdr OR cdrerror) are dropped.</li>
     * </ul>
     */
    public CdrBatchResult RunSmsOutgoing(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs, IAutoIncrementManager ids, int segmentSize,
            boolean legacyDedup, java.util.function.Supplier<java.time.LocalDateTime> clock) {
        if (ids == null)
            throw new IllegalArgumentException("the SMS batch needs the counter-backed id source (SmsIdAllocators.For)");
        return RunCore(conn, mediation, partners, cdrs, ids, segmentSize, null, false,
                c -> {
                    var accounting = new MySqlSmsAccountingStore(c);
                    return CdrPipeline.SmsOutgoing(accounting.LoadBillingRuleCatalog(), accounting, clock);
                },
                c -> FilterSmsSeen(c, cdrs, legacyDedup));
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
     * {@code UniqueBillId} is already in the {@code cdr} table is dropped by {@link #FilterAlreadyBilled} (the
     * unique index is the hard backstop), so re-running never double-bills; a call still failing is simply
     * re-written to {@code cdrerror} with its fresh reason.
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

    private CdrBatchResult RunInternal(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs,
            IAutoIncrementManager ids, int segmentSize, InTxAction preProcess, boolean legacyDedup) {
        return RunCore(conn, mediation, partners, cdrs, ids, segmentSize, preProcess, legacyDedup, c -> _processor, null);
    }

    /** Builds the batch's pipeline over the batch connection (voice: the shared instance). */
    @FunctionalInterface
    private interface PipelineFactory { CdrPipeline For(Connection conn) throws SQLException; }

    /** A batch-specific dedup that REPLACES the voice filters (SMS); null = the voice filters, unchanged. */
    @FunctionalInterface
    private interface DedupFilter { List<cdr> Keep(Connection conn) throws SQLException; }

    private CdrBatchResult RunCore(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs,
            IAutoIncrementManager ids, int segmentSize, InTxAction preProcess, boolean legacyDedup,
            PipelineFactory pipelineFactory, DedupFilter dedupOverride) {
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
            // Reprocess-only: delete the source cdrerror rows here, INSIDE the tx and under the lock, so the
            // move to cdr is atomic (rollback restores them). No-op for the normal ingest path (null action).
            if (preProcess != null) preProcess.run(conn);
            // CUTOVER legacy-ownership dedup (feature-gated; OFF by default). An ADDITIONAL layer BEFORE the
            // normal UniqueBillId idempotency: during the legacy→new cutover a cdr whose SequenceNumber is
            // already owned by legacy (present in THIS tenant's cdr OR cdrerror, on this same connection/schema)
            // is dropped — legacy cdr = billed, legacy cdrerror = failed-and-NOT-recovered; either way NEW
            // billing must not touch it. Only seqs in NEITHER table proceed. Batched (one query per table) and
            // FAIL-SAFE (a lookup SQLException propagates → whole batch rolls back / retries → never a silent bill).
            List<cdr> toProcess;
            if (dedupOverride != null) {
                // SMS: its own combined redelivery + legacy-ownership filter (see FilterSmsSeen).
                toProcess = dedupOverride.Keep(conn);
                int skipped = cdrs.size() - toProcess.size();
                if (skipped > 0)
                    log.infof("sms dedup: skipped %d already-seen sms cdr(s) in %s", skipped, batchLock);
            } else {
                List<cdr> afterLegacy = cdrs;
                if (legacyDedup) {
                    afterLegacy = FilterLegacyOwned(cdrs, seqs -> jdbcOwnedSeqs(conn, seqs));
                    int skippedLegacy = cdrs.size() - afterLegacy.size();
                    if (skippedLegacy > 0)
                        log.infof("cutover legacy-dedup: skipped %d cdr(s) owned by legacy (seq in cdr/cdrerror) in %s",
                                skippedLegacy, batchLock);
                }
                // Cross-batch idempotency (T3): under the per-schema lock (so this SELECT sees the true committed
                // state and no concurrent batch can write between the check and our insert), drop any cdr whose
                // UniqueBillId is ALREADY billed in this schema's cdr table. A redelivered Kafka poll-batch (offsets
                // commit only after the DB commit — at-least-once) therefore cannot double-write / double-bill.
                // The unique index on cdr(UniqueBillId) is the hard backstop if two processes ever race past this.
                toProcess = FilterAlreadyBilled(conn, afterLegacy);
                int skipped = cdrs.size() - toProcess.size();
                if (skipped > 0)
                    log.infof("idempotency: skipped %d already-billed cdr(s) in %s (redelivery)", skipped, batchLock);
            }
            // the pipeline writes EVERYTHING through this connection-bound store — one connection, one transaction.
            var store = new MySqlExecutor(conn);
            var batch = new CdrBatch(mediation, partners, toProcess, store, ids, segmentSize);
            var result = pipelineFactory.For(conn).Process(batch);
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

    // ─────────────────────────────── OUTGOING SMS dedup ───────────────────────────────────────────────

    /** What the SMS dedup found already present: UniqueBillIds and SequenceNumbers (in cdr or cdrerror). */
    record SmsSeen(java.util.Set<String> BillIds, java.util.Set<Long> Seqs) {}

    /** SMS dedup lookup seam (production: {@link #jdbcSmsSeen}); a throw aborts the batch — never a silent bill. */
    @FunctionalInterface
    interface SmsSeenLookup {
        SmsSeen Seen(java.util.Set<String> billIds, java.util.Set<Long> seqs, boolean checkSeqs,
                java.time.LocalDateTime from, java.time.LocalDateTime to) throws SQLException;
    }

    /**
     * The SMS batch filter (replaces the voice filters for this path). Keeps an SMS only when NONE holds:
     * <ol>
     * <li>its {@code UniqueBillId} (= Kafka {@code idCall}) appeared EARLIER IN THIS BATCH — a duplicate record in
     *   one poll must not abort the whole batch (the pipeline's in-batch uniqueness assertion would) and replay
     *   it forever;</li>
     * <li>its {@code UniqueBillId} is already in {@code cdr} OR {@code cdrerror} — a Kafka redelivery of an SMS
     *   this path already billed OR already rejected writes nothing again (no second charge, transaction, debit,
     *   ledger amount, summary or error row);</li>
     * <li>with {@code legacyDedup}: its {@code SequenceNumber} (= {@code idCall}; legacy stores idCall there) is
     *   already in {@code cdr} OR {@code cdrerror} — an SMS legacy billed or rejected is never billed again.</li>
     * </ol>
     * Both tables are partitioned on StartTime, so the lookups are bounded to [min StartTime − 1 day,
     * max StartTime + 1 day) — a redelivered/legacy row of the same SMS carries the same StartTime.
     */
    static List<cdr> FilterSmsSeen(Connection conn, List<cdr> cdrs, boolean legacyDedup) throws SQLException {
        return FilterSmsSeen(cdrs, legacyDedup, (b, s, check, from, to) -> jdbcSmsSeen(conn, b, s, check, from, to));
    }

    static List<cdr> FilterSmsSeen(List<cdr> cdrs, boolean legacyDedup, SmsSeenLookup lookup) throws SQLException {
        var unique = new ArrayList<cdr>(cdrs.size());
        var inBatch = new HashSet<String>();
        for (cdr c : cdrs) {
            if (c.UniqueBillId == null || c.UniqueBillId.isEmpty())
                throw new IllegalStateException("SMS cdr without UniqueBillId (idCall) reached the batch");
            if (inBatch.add(c.UniqueBillId)) unique.add(c);
        }
        if (unique.isEmpty()) return unique;

        var billIds = new LinkedHashSet<String>();
        var seqs = new LinkedHashSet<Long>();
        java.time.LocalDateTime min = null, max = null;
        for (cdr c : unique) {
            billIds.add(c.UniqueBillId);
            if (c.SequenceNumber > 0) seqs.add(c.SequenceNumber);
            if (c.StartTime != null) {
                if (min == null || c.StartTime.isBefore(min)) min = c.StartTime;
                if (max == null || c.StartTime.isAfter(max)) max = c.StartTime;
            }
        }
        java.time.LocalDateTime from = min != null ? min.minusDays(1) : null;
        java.time.LocalDateTime to = max != null ? max.plusDays(1) : null;
        SmsSeen seen = lookup.Seen(billIds, seqs, legacyDedup, from, to);

        var kept = new ArrayList<cdr>(unique.size());
        for (cdr c : unique) {
            if (seen.BillIds().contains(c.UniqueBillId)) continue;
            if (legacyDedup && c.SequenceNumber > 0 && seen.Seqs().contains(c.SequenceNumber)) continue;
            kept.add(c);
        }
        return kept;
    }

    /** Production SMS lookup: UniqueBillId (and, when asked, SequenceNumber) IN-checks on cdr + cdrerror. */
    static SmsSeen jdbcSmsSeen(Connection conn, java.util.Set<String> billIds, java.util.Set<Long> seqs,
            boolean checkSeqs, java.time.LocalDateTime from, java.time.LocalDateTime to) throws SQLException {
        var foundBills = new HashSet<String>();
        var foundSeqs = new HashSet<Long>();
        for (String table : List.of("cdr", "cdrerror")) {
            SelectWindowed(conn, table, "UniqueBillId", new ArrayList<>(billIds), from, to,
                    (ps, i, v) -> ps.setString(i, (String) v), rs -> foundBills.add(rs.getString(1)));
            if (checkSeqs && !seqs.isEmpty())
                SelectWindowed(conn, table, "SequenceNumber", new ArrayList<>(seqs), from, to,
                        (ps, i, v) -> ps.setLong(i, (Long) v), rs -> foundSeqs.add(rs.getLong(1)));
        }
        return new SmsSeen(foundBills, foundSeqs);
    }

    @FunctionalInterface private interface Binder { void bind(PreparedStatement ps, int index, Object value) throws SQLException; }
    @FunctionalInterface private interface RowSink { void take(ResultSet rs) throws SQLException; }

    private static void SelectWindowed(Connection conn, String table, String column, List<?> values,
            java.time.LocalDateTime from, java.time.LocalDateTime to, Binder binder, RowSink sink) throws SQLException {
        final int chunk = 500;
        for (int i = 0; i < values.size(); i += chunk) {
            var slice = values.subList(i, Math.min(i + chunk, values.size()));
            var sql = new StringBuilder("select ").append(column).append(" from ").append(table).append(" where ");
            if (from != null && to != null) sql.append("StartTime >= ? and StartTime < ? and ");
            sql.append(column).append(" in (");
            for (int j = 0; j < slice.size(); j++) sql.append(j == 0 ? "?" : ",?");
            sql.append(")");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int p = 1;
                if (from != null && to != null) {
                    ps.setTimestamp(p++, java.sql.Timestamp.valueOf(from));
                    ps.setTimestamp(p++, java.sql.Timestamp.valueOf(to));
                }
                for (Object v : slice) binder.bind(ps, p++, v);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) sink.take(rs);
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

    /** The ingest call-site overload: default ids/segment, with the explicit cutover {@code legacyDedup} switch. */
    public CdrBatchResult Run(Connection conn, MediationContext mediation,
            Map<Integer, Partner> partners, List<cdr> cdrs, boolean legacyDedup) {
        return Run(conn, mediation, partners, cdrs, null, BatchSqlWriter.DefaultSegmentSize, legacyDedup);
    }
}
