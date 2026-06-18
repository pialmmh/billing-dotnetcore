using MediationModel;

namespace Billing.Mediation.Rating;

/// <summary>
/// Resolves which rate-plan-assignment tuples apply to a call — the lean port of legacy
/// <c>A2ZRater.GetAssignmentTuples</c>. Tuples are indexed by the legacy <c>GetTuple()</c> key forms
/// (<c>idService/dir/route</c> and <c>idService/dir/idpartner</c>); resolution tries the route scope
/// first, then the partner scope, returning the matching tuples priority-ordered for
/// <see cref="PrefixMatcher"/> to longest-prefix over their <c>rateassigns</c>.
/// </summary>
public sealed class RatePlanResolver
{
    private readonly IReadOnlyDictionary<string, IReadOnlyList<rateplanassignmenttuple>> _routeIndex;
    private readonly IReadOnlyDictionary<string, IReadOnlyList<rateplanassignmenttuple>> _partnerIndex;

    private RatePlanResolver(
        IReadOnlyDictionary<string, IReadOnlyList<rateplanassignmenttuple>> routeIndex,
        IReadOnlyDictionary<string, IReadOnlyList<rateplanassignmenttuple>> partnerIndex)
    {
        _routeIndex = routeIndex;
        _partnerIndex = partnerIndex;
    }

    public static RatePlanResolver Build(IReadOnlyList<rateplanassignmenttuple> tuples)
    {
        var route = new Dictionary<string, List<rateplanassignmenttuple>>();
        var partner = new Dictionary<string, List<rateplanassignmenttuple>>();

        foreach (var t in tuples)
        {
            if (t.route is > 0)
                Add(route, RouteKey(t.idService, t.AssignDirection, t.route.Value), t);
            else if (t.idpartner is > 0)
                Add(partner, PartnerKey(t.idService, t.AssignDirection, t.idpartner.Value), t);
        }

        return new RatePlanResolver(Freeze(route), Freeze(partner));
    }

    /// <summary>The tuples that apply to the call (route scope preferred over partner scope),
    /// priority-ordered; empty if none. PrefixMatcher then longest-prefixes over their rateassigns.</summary>
    public IReadOnlyList<rateplanassignmenttuple> Resolve(int idService, int assignDirection, int? idPartner, int? route)
    {
        if (route is > 0 &&
            _routeIndex.TryGetValue(RouteKey(idService, assignDirection, route.Value), out var rt))
            return rt;

        if (idPartner is > 0 &&
            _partnerIndex.TryGetValue(PartnerKey(idService, assignDirection, idPartner.Value), out var pt))
            return pt;

        return Array.Empty<rateplanassignmenttuple>();
    }

    private static string RouteKey(int idService, int dir, int route) => $"{idService}/{dir}/{route}";
    private static string PartnerKey(int idService, int dir, int idPartner) => $"{idService}/{dir}/{idPartner}";

    private static void Add(Dictionary<string, List<rateplanassignmenttuple>> index, string key, rateplanassignmenttuple t)
    {
        if (!index.TryGetValue(key, out var list)) index[key] = list = new List<rateplanassignmenttuple>();
        list.Add(t);
    }

    private static IReadOnlyDictionary<string, IReadOnlyList<rateplanassignmenttuple>> Freeze(
        Dictionary<string, List<rateplanassignmenttuple>> index)
    {
        var frozen = new Dictionary<string, IReadOnlyList<rateplanassignmenttuple>>(index.Count);
        foreach (var (key, list) in index)
            frozen[key] = list.OrderBy(t => t.priority).ToList();   // lowest priority first
        return frozen;
    }

    public static RatePlanResolver Empty { get; } = Build([]);
}
