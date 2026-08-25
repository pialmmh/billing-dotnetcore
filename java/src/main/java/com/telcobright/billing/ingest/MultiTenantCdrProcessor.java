package com.telcobright.billing.ingest;

import com.telcobright.billing.beans.CdrProcessingResult;
import com.telcobright.billing.beans.CdrProcessor;
import org.jboss.logging.Logger;

/**
 * Writes ALL tiers of one preprocessed poll-batch, each to its OWN schema. Consumes a
 * {@link MultiTenantCdrBatch} (already grouped by target tenant/dbName by {@link CdrEventPreprocessor}) and
 * runs each {@link PerTenantCdrs} slice through {@link CdrProcessor#ProcessBatch}, which opens ONE connection +
 * ONE transaction on THAT tenant's schema and writes its cdr + cdrerror + acc_chargeable + summary_affected.
 *
 * <p><b>Isolation.</b> Each tenant slice carries its own dbName and its own {@code Context.MediationContext}
 * (rate plans + per-tenant RateCache), so a tier's rate lookup and its writes stay within its own database —
 * there is no cross-schema read or write here.
 *
 * <p><b>Atomicity model.</b> True ACID across DIFFERENT MySQL databases is not available on plain JDBC (no
 * XA/2PC here), so this commits PER TENANT, not as one cross-schema transaction. Redelivery safety therefore
 * comes from IDEMPOTENCY, not a distributed transaction: {@code MySqlCdrBatchRunner} dedups on
 * {@code cdr.UniqueBillId} under the per-schema lock (backed by a unique index), so a poll-batch that is
 * redelivered after a partial failure re-writes nothing that already committed. The caller (the Kafka
 * consumer) commits offsets only after {@link #Process} returns without throwing; if ANY tenant slice fails to
 * commit, this throws and the whole poll-batch is retried — the already-committed tenants are then skipped by
 * the dedup on retry.
 */
public final class MultiTenantCdrProcessor {
    private final CdrProcessor _processor;
    private final Logger log;

    public MultiTenantCdrProcessor(CdrProcessor processor, Logger log) {
        this._processor = processor;
        this.log = log;
    }

    /** The aggregate outcome across all tenant slices of one poll-batch. */
    public record Result(int tenants, int committed, int rated, int errored, int chargeables) {
    }

    /**
     * Write every tenant slice to its own schema. Throws {@link IllegalStateException} if any slice did not
     * commit — the caller must then NOT commit Kafka offsets, so the poll-batch is redelivered and the
     * already-committed tenants are skipped by the per-schema dedup.
     */
    public Result Process(MultiTenantCdrBatch batch) {
        int tenants = 0, committed = 0, rated = 0, errored = 0, chargeables = 0;
        for (PerTenantCdrs t : batch.tenants()) {
            tenants++;
            CdrProcessingResult r = _processor.ProcessBatch(t.tenant(), t.cdrs());
            if (!r.Committed())
                throw new IllegalStateException("tenant '" + t.tenant() + "' batch not committed: " + r.Error());
            committed++;
            if (r.Batch() != null) {
                rated += r.Batch().Rated().size();
                errored += r.Batch().Errored().size();
                chargeables += r.Batch().ChargeablesWritten();
            }
        }
        if (tenants > 0)
            log.infof("multi-tenant cdr write: %d tenant(s) committed, %d rated, %d errored, %d chargeables",
                    committed, rated, errored, chargeables);
        return new Result(tenants, committed, rated, errored, chargeables);
    }
}
