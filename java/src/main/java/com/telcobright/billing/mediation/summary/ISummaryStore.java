package com.telcobright.billing.mediation.summary;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

/**
 * The DB seam for the summary subsystem — the load side of PopulatePrevSummary (the legacy
 * TimeWiseSummaryCachePopulator) plus the write side (ISqlExecutor the cache's WriteAllChanges runs
 * through). One implementation wraps the single per-call MySqlConnection; tests use an in-memory fake.
 * Keeping the DB behind this one interface is what makes the whole subsystem testable without a live
 * database — only this implementation needs the DB go-ahead.
 */
public interface ISummaryStore extends ISqlExecutor {
    /**
     * Existing persisted rows for a summary table whose {@code tup_starttime} is one of the given values
     * (the bucketed start times the call touches). These seed the cache so new calls merge onto existing
     * totals.
     */
    List<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, Collection<LocalDateTime> startTimes);
}
