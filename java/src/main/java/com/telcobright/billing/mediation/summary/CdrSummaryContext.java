package com.telcobright.billing.mediation.summary;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
// List<Object> (the legacy 20-field ValueTuple) is represented as java.util.List<Object> (see AbstractCdrSummary).
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.summary.cache.IAutoIncrementManager;
import com.telcobright.billing.mediation.summary.cache.MySqlFieldExtensions;
import com.telcobright.billing.mediation.summary.cache.SummaryCache;
import com.telcobright.billing.mediation.summary.cache.SummaryMergeType;

/**
 * Port of legacy {@code CdrSummaryContext} — the per-table summary caches and the orchestration around
 * them: PopulatePrevSummary (load existing rows so calls merge onto them), GenerateSummary (build a call's
 * per-table summaries via CdrSummaryBuilder), MergeAddSummary/MergeSubstractSummary, and WriteAllChanges.
 * Each table's cache is a {@code SummaryCache<AbstractCdrSummary, List<Object>>} (TEntity is the base
 * type; the concrete sum_voice_* instance rides inside, and the table name is substituted into the
 * UPDATE/DELETE templates — exactly as legacy {@code CreateSummaryCacheInstance} did).
 */
public final class CdrSummaryContext {
    // Service group -> its summary target tables (legacy IServiceGroup.GetSummaryTargetTables).
    private static final Map<Integer, CdrSummaryType[]> ServiceGroupTargetTables = Map.of(
            10, new CdrSummaryType[] { CdrSummaryType.sum_voice_day_03, CdrSummaryType.sum_voice_hr_03 },
            11, new CdrSummaryType[] { CdrSummaryType.sum_voice_day_02, CdrSummaryType.sum_voice_hr_02 });

    private final ISummaryStore _store;
    private final IAutoIncrementManager _ids;
    private final Map<CdrSummaryType, SummaryCache<AbstractCdrSummary, List<Object>>> _caches = new HashMap<>();

    public CdrSummaryContext(ISummaryStore store, IAutoIncrementManager ids) {
        _store = store;
        _ids = ids;
    }

    public Map<CdrSummaryType, SummaryCache<AbstractCdrSummary, List<Object>>> TableWiseSummaryCache() {
        return _caches;
    }

    /**
     * For the given service groups, create each target table's cache and seed it with the existing rows for
     * ALL the buckets the batch touches — day tables &larr; datesInvolved, hr tables &larr; hoursInvolved
     * (the legacy DatesInvolved/HoursInvolved, each loaded once per table in a single
     * {@code tup_starttime IN (...)} query). Every call then merges onto whichever loaded bucket it falls
     * in — so a multi-day/multi-hour batch merges onto existing rows instead of duplicating them.
     */
    public void PopulatePrevSummary(List<Integer> serviceGroupIds,
            Collection<LocalDateTime> datesInvolved, Collection<LocalDateTime> hoursInvolved) {
        for (Integer serviceGroup : serviceGroupIds) {
            CdrSummaryType[] tables = ServiceGroupTargetTables.get(serviceGroup);
            if (tables == null) continue;
            for (CdrSummaryType table : tables) {
                if (_caches.containsKey(table)) continue;
                SummaryCache<AbstractCdrSummary, List<Object>> cache = CreateSummaryCacheInstance(table);
                Collection<LocalDateTime> startTimes = IsHourly(table) ? hoursInvolved : datesInvolved;
                if (startTimes.size() > 0)
                    for (AbstractCdrSummary existing : _store.LoadByStartTimes(table, startTimes))
                        cache.PopulateExisting(existing);
                _caches.put(table, cache);
            }
        }
    }

    /**
     * Build the call's summary for each of its service group's target tables (legacy GenerateSummary).
     * Keyed by table; the caller merges them (or use AddCall).
     */
    public Map<CdrSummaryType, AbstractCdrSummary> GenerateSummary(cdr cdr, acc_chargeable customerChargeable) {
        Map<CdrSummaryType, AbstractCdrSummary> result = new HashMap<>();
        CdrSummaryType[] tables = ServiceGroupTargetTables.get(customerChargeable.servicegroup);
        if (tables == null) return result;
        for (CdrSummaryType table : tables)
            result.put(table, CdrSummaryBuilder.Build(cdr, customerChargeable,
                    IsHourly(table) ? SummaryBucket.Hour : SummaryBucket.Day));
        return result;
    }

    /**
     * Generate the call's summaries and merge-add them into the caches (GenerateSummary +
     * MergeNewSummariesIntoCache, per call).
     */
    public void AddCall(cdr cdr, acc_chargeable customerChargeable) {
        for (Map.Entry<CdrSummaryType, AbstractCdrSummary> e : GenerateSummary(cdr, customerChargeable).entrySet())
            MergeAddSummary(e.getKey(), e.getValue());
    }

    public void MergeAddSummary(CdrSummaryType table, AbstractCdrSummary summary) {
        GetCache(table).Merge(summary, SummaryMergeType.Add, s -> s.id > 0);
    }

    public void MergeSubstractSummary(CdrSummaryType table, AbstractCdrSummary summary) {
        GetCache(table).Merge(summary, SummaryMergeType.Substract, s -> s.id > 0);
    }

    /**
     * Flush every table's inserts + updates through the store's single-connection executor, segmented into
     * segmentSize-row batches (legacy SegmentSizeForDbWrite).
     */
    public void WriteAllChanges() {
        WriteAllChanges(BatchSqlWriter.DefaultSegmentSize);
    }

    public void WriteAllChanges(int segmentSize) {
        for (SummaryCache<AbstractCdrSummary, List<Object>> cache : _caches.values())
            cache.WriteAllChanges(_store, segmentSize);
    }

    private static boolean IsHourly(CdrSummaryType table) {
        return table.toString().contains("_hr_");
    }

    private SummaryCache<AbstractCdrSummary, List<Object>> GetCache(CdrSummaryType table) {
        SummaryCache<AbstractCdrSummary, List<Object>> cache = _caches.get(table);
        if (cache == null) {
            cache = CreateSummaryCacheInstance(table);
            _caches.put(table, cache);
        }
        return cache;
    }

    // Legacy CdrSummaryContext.CreateSummaryCacheInstance: the UPDATE/DELETE templates are built on the
    // "AbstractCdrSummary" placeholder and the concrete table name is substituted in.
    private SummaryCache<AbstractCdrSummary, List<Object>> CreateSummaryCacheInstance(CdrSummaryType table) {
        final String name = table.toString();
        Function<AbstractCdrSummary, String> where =
                s -> " where id=" + s.id + " and tup_starttime=" + MySqlFieldExtensions.ToMySqlField(s.tup_starttime);

        return new SummaryCache<AbstractCdrSummary, List<Object>>(
                name, _ids,
                s -> s.GetTupleKey(),
                s -> s.GetExtInsertValues(),
                s -> new StringBuilder(s.GetUpdateCommand(where).toString().replace("AbstractCdrSummary", name)),
                s -> new StringBuilder(s.GetDeleteCommand(where).toString().replace("AbstractCdrSummary", name)),
                "insert into " + name + " (" + AbstractCdrSummary.ExtInsertColumns + ") values ");
    }
}
