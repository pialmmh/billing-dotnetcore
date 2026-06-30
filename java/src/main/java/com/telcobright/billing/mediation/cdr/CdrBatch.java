package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.summary.cache.IAutoIncrementManager;
import com.telcobright.billing.mediation.summary.ISummaryStore;

import java.util.List;
import java.util.Map;

/**
 * The processing input for ONE tenant's already-fetched cdr batch: the tenant's rating config
 * ({@link MediationContext} + {@code Partners}), the cdrs, and the summary store the writes
 * flow through (wraps that tenant's single per-batch connection). The job layer assembles this.
 */
public record CdrBatch(
        MediationContext Mediation,
        Map<Integer, Partner> Partners,
        List<cdr> Cdrs,
        ISummaryStore SummaryStore,
        IAutoIncrementManager Ids,
        int SegmentSize) {

    // Default-parameter overloads — the C# record had `Ids = null, SegmentSize = DefaultSegmentSize`.
    // Java has no default args, so each shorter call site gets a constructor that fills the trailing
    // defaults and delegates to the canonical constructor.
    public CdrBatch(MediationContext Mediation, Map<Integer, Partner> Partners, List<cdr> Cdrs,
                    ISummaryStore SummaryStore) {
        this(Mediation, Partners, Cdrs, SummaryStore, null, BatchSqlWriter.DefaultSegmentSize);
    }

    public CdrBatch(MediationContext Mediation, Map<Integer, Partner> Partners, List<cdr> Cdrs,
                    ISummaryStore SummaryStore, IAutoIncrementManager Ids) {
        this(Mediation, Partners, Cdrs, SummaryStore, Ids, BatchSqlWriter.DefaultSegmentSize);
    }
}
