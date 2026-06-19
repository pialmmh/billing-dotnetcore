using System.Text;
using LibraryExtensions;
using MediationModel;
using MediationModel.enums;
using TelcobrightMediation;
using CdrSummaryTuple = System.ValueTuple<int, int, int, string, string, decimal, decimal,
    System.ValueTuple<string, string, string, string, string, string, string,
        System.ValueTuple<string, string, string, string, string, string>>>;

namespace Billing.Mediation.Summary;

/// <summary>
/// Port of legacy <c>CdrSummaryContext</c> — the per-table summary caches and the orchestration around
/// them: <see cref="PopulatePrevSummary"/> (load existing rows so calls merge onto them),
/// <see cref="GenerateSummary"/> (build a call's per-table summaries via <see cref="CdrSummaryBuilder"/>),
/// <see cref="MergeAddSummary"/>/<see cref="MergeSubstractSummary"/>, and <see cref="WriteAllChanges"/>.
/// Each table's cache is a <c>SummaryCache&lt;AbstractCdrSummary, CdrSummaryTuple&gt;</c> (TEntity is the
/// base type; the concrete sum_voice_* instance rides inside, and the table name is substituted into the
/// UPDATE/DELETE templates — exactly as legacy <c>CreateSummaryCacheInstance</c> did).
/// </summary>
public sealed class CdrSummaryContext
{
    // Service group -> its summary target tables (legacy IServiceGroup.GetSummaryTargetTables).
    private static readonly IReadOnlyDictionary<int, CdrSummaryType[]> ServiceGroupTargetTables =
        new Dictionary<int, CdrSummaryType[]>
        {
            [10] = new[] { CdrSummaryType.sum_voice_day_03, CdrSummaryType.sum_voice_hr_03 },
            [11] = new[] { CdrSummaryType.sum_voice_day_02, CdrSummaryType.sum_voice_hr_02 },
        };

    private readonly ISummaryStore _store;
    private readonly IAutoIncrementManager _ids;
    private readonly Dictionary<CdrSummaryType, SummaryCache<AbstractCdrSummary, CdrSummaryTuple>> _caches = new();

    public CdrSummaryContext(ISummaryStore store, IAutoIncrementManager ids)
    {
        _store = store;
        _ids = ids;
    }

    public IReadOnlyDictionary<CdrSummaryType, SummaryCache<AbstractCdrSummary, CdrSummaryTuple>> TableWiseSummaryCache => _caches;

    /// <summary>For the given service groups, create each target table's cache and seed it with the
    /// existing rows for the call's bucketed start times (day tables ← <paramref name="dayStart"/>,
    /// hr tables ← <paramref name="hourStart"/>). The legacy PopulatePrevSummary, scoped to one call.</summary>
    public void PopulatePrevSummary(IEnumerable<int> serviceGroupIds, DateTime dayStart, DateTime hourStart)
    {
        foreach (var serviceGroup in serviceGroupIds)
        {
            if (!ServiceGroupTargetTables.TryGetValue(serviceGroup, out var tables)) continue;
            foreach (var table in tables)
            {
                if (_caches.ContainsKey(table)) continue;
                var cache = CreateSummaryCacheInstance(table);
                var startTimes = IsHourly(table) ? new[] { hourStart } : new[] { dayStart };
                foreach (var existing in _store.LoadByStartTimes(table, startTimes))
                    cache.PopulateExisting(existing);
                _caches[table] = cache;
            }
        }
    }

    /// <summary>Build the call's summary for each of its service group's target tables (legacy
    /// GenerateSummary). Keyed by table; the caller merges them (or use <see cref="AddCall"/>).</summary>
    public Dictionary<CdrSummaryType, AbstractCdrSummary> GenerateSummary(cdr cdr, acc_chargeable customerChargeable)
    {
        var result = new Dictionary<CdrSummaryType, AbstractCdrSummary>();
        if (!ServiceGroupTargetTables.TryGetValue(customerChargeable.servicegroup, out var tables)) return result;
        foreach (var table in tables)
            result[table] = CdrSummaryBuilder.Build(cdr, customerChargeable, IsHourly(table) ? SummaryBucket.Hour : SummaryBucket.Day);
        return result;
    }

    /// <summary>Generate the call's summaries and merge-add them into the caches (GenerateSummary +
    /// MergeNewSummariesIntoCache, per call).</summary>
    public void AddCall(cdr cdr, acc_chargeable customerChargeable)
    {
        foreach (var (table, summary) in GenerateSummary(cdr, customerChargeable))
            MergeAddSummary(table, summary);
    }

    public void MergeAddSummary(CdrSummaryType table, AbstractCdrSummary summary) =>
        GetCache(table).Merge(summary, SummaryMergeType.Add, s => s.id > 0);

    public void MergeSubstractSummary(CdrSummaryType table, AbstractCdrSummary summary) =>
        GetCache(table).Merge(summary, SummaryMergeType.Substract, s => s.id > 0);

    /// <summary>Flush every table's inserts + updates through the store's single-connection executor.</summary>
    public void WriteAllChanges()
    {
        foreach (var cache in _caches.Values)
            cache.WriteAllChanges(_store);
    }

    private static bool IsHourly(CdrSummaryType table) => table.ToString().Contains("_hr_");

    private SummaryCache<AbstractCdrSummary, CdrSummaryTuple> GetCache(CdrSummaryType table)
    {
        if (!_caches.TryGetValue(table, out var cache))
        {
            cache = CreateSummaryCacheInstance(table);
            _caches[table] = cache;
        }
        return cache;
    }

    // Legacy CdrSummaryContext.CreateSummaryCacheInstance: the UPDATE/DELETE templates are built on the
    // "AbstractCdrSummary" placeholder and the concrete table name is substituted in.
    private SummaryCache<AbstractCdrSummary, CdrSummaryTuple> CreateSummaryCacheInstance(CdrSummaryType table)
    {
        var name = table.ToString();
        string Where(AbstractCdrSummary s) => $" where id={s.id} and tup_starttime={s.tup_starttime.ToMySqlField()}";

        return new SummaryCache<AbstractCdrSummary, CdrSummaryTuple>(
            name, _ids,
            s => s.GetTupleKey(),
            s => s.GetExtInsertValues(),
            s => new StringBuilder(s.GetUpdateCommand(Where).ToString().Replace("AbstractCdrSummary", name)),
            s => new StringBuilder(s.GetDeleteCommand(Where).ToString().Replace("AbstractCdrSummary", name)),
            insertHeader: $"insert into {name} ({AbstractCdrSummary.ExtInsertColumns}) values ");
    }
}
