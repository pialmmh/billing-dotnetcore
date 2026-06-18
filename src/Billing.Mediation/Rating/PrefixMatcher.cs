using MediationModel;

namespace Billing.Mediation.Rating;

/// <summary>
/// Faithful port of legacy <c>PrefixMatcher.MatchPrefix</c> over a call's resolved
/// <see cref="rateplanassignmenttuple"/>s. Three nested selections, exactly as the legacy:
/// <list type="number">
/// <item>tuples in <c>priority</c> order (lowest first);</item>
/// <item>longest matching <c>Prefix</c> of the dialed number within a tuple;</item>
/// <item>a rate valid for the call: Category/SubCategory match, active, and answerTime within
///   [startdate, enddate).</item>
/// </list>
/// The first match wins. (The legacy <c>Rateext.P_Startdate/P_Enddate</c> also fold in the rate-plan
/// assignment window via <c>AssignmentFlag</c>; here we use the rateassign's own startdate/enddate — the
/// <c>AssignmentFlag == 0</c> case — and note the rate-plan-bounded case as deferred.)
/// </summary>
public sealed class PrefixMatcher
{
    private static readonly DateTime MaxDate = new(9999, 12, 31, 23, 59, 59);

    // Per tuple (priority order): the best valid rate per prefix string.
    private readonly List<IReadOnlyDictionary<string, rateassign>> _perTuplePrefixIndex = new();

    public PrefixMatcher(IReadOnlyList<rateplanassignmenttuple> tuples, int category, int subCategory, DateTime answerTime)
    {
        foreach (var tuple in tuples.OrderBy(t => t.priority))
        {
            var byPrefix = new Dictionary<string, rateassign>();
            foreach (var rate in tuple.rateassigns)
            {
                if (!IsValid(rate, category, subCategory, answerTime)) continue;
                var prefix = rate.Prefix.ToString();
                // Among rates sharing a prefix, the latest-starting valid one wins (legacy: rates desc by start).
                if (!byPrefix.TryGetValue(prefix, out var existing) || rate.startdate > existing.startdate)
                    byPrefix[prefix] = rate;
            }
            _perTuplePrefixIndex.Add(byPrefix);
        }
    }

    /// <summary>The matched rate for the dialed number, or null. Longest prefix wins within the
    /// lowest-priority tuple that has any match.</summary>
    public rateassign? MatchPrefix(string number)
    {
        if (string.IsNullOrEmpty(number)) return null;

        foreach (var byPrefix in _perTuplePrefixIndex)
        {
            for (var len = number.Length; len >= 1; len--)
            {
                if (byPrefix.TryGetValue(number.Substring(0, len), out var rate))
                    return rate;
            }
        }
        return null;
    }

    private static bool IsValid(rateassign rate, int category, int subCategory, DateTime answerTime) =>
        rate.Inactive == 0
        && rate.Category.HasValue && rate.Category.Value == category
        && rate.SubCategory.HasValue && rate.SubCategory.Value == subCategory
        && answerTime >= rate.startdate
        && answerTime < (rate.enddate ?? MaxDate);
}
