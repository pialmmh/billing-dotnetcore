using MediationModel;
using CdrSummaryTuple = System.ValueTuple<int, int, int, string, string, decimal, decimal,
    System.ValueTuple<string, string, string, string, string, string, string,
        System.ValueTuple<string, string, string, string, string, string>>>;

namespace Billing.Mediation.Summary;

/// <summary>
/// Rolls per-call <see cref="AbstractCdrSummary"/> rows up by their <see cref="AbstractCdrSummary.GetTupleKey"/>
/// — the in-memory accumulate-then-merge the legacy summary context does before the single write. Calls
/// that share an identity tuple (switch/partners/routes/rates/prefixes/currencies + the bucketed start
/// time) collapse into one row whose count/duration/cost/tax fields are summed (<c>Merge</c>).
/// One cache per tier schema; the (deferred) single-connection write persists <see cref="Items"/>.
/// </summary>
public sealed class SummaryCache
{
    private readonly Dictionary<CdrSummaryTuple, AbstractCdrSummary> _byKey = new();

    /// <summary>Add a per-call summary: merge into the existing rollup for its key, or seed a new one.</summary>
    public void Add(AbstractCdrSummary summary)
    {
        var key = summary.GetTupleKey();
        if (_byKey.TryGetValue(key, out var existing)) existing.Merge(summary);
        else _byKey[key] = summary;
    }

    /// <summary>The aggregated rows, one per distinct tuple key.</summary>
    public IReadOnlyCollection<AbstractCdrSummary> Items => _byKey.Values;

    public int Count => _byKey.Count;
}
