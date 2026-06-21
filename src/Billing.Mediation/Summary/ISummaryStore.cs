using Billing.Mediation.Sql;
using MediationModel;
using MediationModel.enums;

namespace Billing.Mediation.Summary;

/// <summary>
/// The DB seam for the summary subsystem — the load side of PopulatePrevSummary (the legacy
/// TimeWiseSummaryCachePopulator) plus the write side (<see cref="ISqlExecutor"/> the cache's
/// WriteAllChanges runs through). One implementation wraps the single per-call MySqlConnection; tests use
/// an in-memory fake. Keeping the DB behind this one interface is what makes the whole subsystem testable
/// without a live database — only this implementation needs the DB go-ahead.
/// </summary>
public interface ISummaryStore : ISqlExecutor
{
    /// <summary>Existing persisted rows for a summary table whose <c>tup_starttime</c> is one of the given
    /// values (the bucketed start times the call touches). These seed the cache so new calls merge onto
    /// existing totals.</summary>
    IReadOnlyList<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, IReadOnlyCollection<DateTime> startTimes);
}

/// <summary>A simple monotonic id source per table (legacy IAutoIncrementManager). The live implementation
/// seeds each counter from <c>max(id)</c> of the tier table; this default just counts from a base.</summary>
public sealed class CountingAutoIncrementManager : IAutoIncrementManager
{
    private readonly Dictionary<string, long> _next = new();
    private readonly long _base;

    public CountingAutoIncrementManager(long start = 1) => _base = start;

    public long GetNewCounter(string entityOrTableName)
    {
        var value = _next.TryGetValue(entityOrTableName, out var n) ? n : _base;
        _next[entityOrTableName] = value + 1;
        return value;
    }
}
