using Billing.Mediation.Model;

namespace Billing.Mediation.Rating;

/// <summary>
/// Today-only rate lookup for one tenant. Built FROM config-manager's already-today-scoped
/// <c>ratePlanWiseTodaysRates</c> (ratePlanId → prefix → Rate) — NOT the C# date-range cache. The
/// validity window (startDate/endDate) is already applied upstream by the config-manager query, so this
/// cache holds only today-valid rates; what it adds is the LONGEST-PREFIX match (the C# Sg* semantics).
///
/// It is immutable and stamped with the date it was built for, so a day rollover is detectable
/// (<see cref="IsStale"/>); the day-boundary refresher rebuilds it because CDC config-events do not fire
/// at midnight.
/// </summary>
public sealed class RateCache
{
    // ratePlanId -> (prefix, Rate) pairs sorted longest-prefix-first, so the first startsWith match wins.
    private readonly IReadOnlyDictionary<int, IReadOnlyList<KeyValuePair<string, Rate>>> _byPlan;

    public DateOnly BuiltForDate { get; }

    private RateCache(IReadOnlyDictionary<int, IReadOnlyList<KeyValuePair<string, Rate>>> byPlan, DateOnly builtForDate)
    {
        _byPlan = byPlan;
        BuiltForDate = builtForDate;
    }

    public static RateCache Build(
        IReadOnlyDictionary<int, IReadOnlyDictionary<string, Rate>> todaysRates, DateOnly builtForDate)
    {
        var byPlan = new Dictionary<int, IReadOnlyList<KeyValuePair<string, Rate>>>(todaysRates.Count);
        foreach (var (ratePlanId, prefixMap) in todaysRates)
            byPlan[ratePlanId] = prefixMap.OrderByDescending(kv => kv.Key.Length).ToList();
        return new RateCache(byPlan, builtForDate);
    }

    /// <summary>The rate whose prefix is the LONGEST prefix of the dialed number within the plan, or null.</summary>
    public Rate? FindRate(int ratePlanId, string dialedNumber)
    {
        if (!_byPlan.TryGetValue(ratePlanId, out var sorted)) return null;
        foreach (var (prefix, rate) in sorted)
            if (dialedNumber.StartsWith(prefix, StringComparison.Ordinal))
                return rate;
        return null;
    }

    /// <summary>True when the cache was built for a different day than <paramref name="today"/> —
    /// the signal for the day-boundary refresher to rebuild today's rates.</summary>
    public bool IsStale(DateOnly today) => today != BuiltForDate;

    public static RateCache Empty { get; } =
        new(new Dictionary<int, IReadOnlyList<KeyValuePair<string, Rate>>>(), default);
}
