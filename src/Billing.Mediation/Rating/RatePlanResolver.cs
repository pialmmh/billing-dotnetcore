using Billing.Mediation.Model;

namespace Billing.Mediation.Rating;

/// <summary>
/// Resolves which rate plan applies to a call — the lean port of legacy
/// <c>A2ZRater.GetAssignmentTuples</c>. Tuples are indexed by the legacy key forms
/// (<c>idService/dir/route</c> and <c>idService/dir/idpartner</c>); resolution tries the route scope
/// first, then the partner scope, and the lowest <c>priority</c> wins within a key.
///
/// The winning tuple's <see cref="RatePlanAssignmentTuple.IdRatePlan"/> then selects the plan in the
/// today-only <see cref="RateCache"/>. (The legacy fed all matching tuples to PrefixMatcher to blend
/// rates by prefix+priority; for the SG10/11 customer slice the lowest-priority tuple is enough — multi-
/// tuple prefix blending is a deferred refinement.)
/// </summary>
public sealed class RatePlanResolver
{
    private readonly IReadOnlyDictionary<string, IReadOnlyList<RatePlanAssignmentTuple>> _routeIndex;
    private readonly IReadOnlyDictionary<string, IReadOnlyList<RatePlanAssignmentTuple>> _partnerIndex;

    private RatePlanResolver(
        IReadOnlyDictionary<string, IReadOnlyList<RatePlanAssignmentTuple>> routeIndex,
        IReadOnlyDictionary<string, IReadOnlyList<RatePlanAssignmentTuple>> partnerIndex)
    {
        _routeIndex = routeIndex;
        _partnerIndex = partnerIndex;
    }

    public static RatePlanResolver Build(IReadOnlyList<RatePlanAssignmentTuple> tuples)
    {
        var route = new Dictionary<string, List<RatePlanAssignmentTuple>>();
        var partner = new Dictionary<string, List<RatePlanAssignmentTuple>>();

        foreach (var t in tuples)
        {
            if (t.Route is > 0)
                Add(route, RouteKey(t.IdService, t.AssignDirection, t.Route.Value), t);
            else if (t.IdPartner is > 0)
                Add(partner, PartnerKey(t.IdService, t.AssignDirection, t.IdPartner.Value), t);
        }

        return new RatePlanResolver(Freeze(route), Freeze(partner));
    }

    /// <summary>The best tuple for the call, or null if none is assigned. Route scope beats partner
    /// scope; lowest priority wins.</summary>
    public RatePlanAssignmentTuple? Resolve(int idService, int assignDirection, int? idPartner, int? route)
    {
        if (route is > 0 &&
            _routeIndex.TryGetValue(RouteKey(idService, assignDirection, route.Value), out var rt))
            return rt[0];

        if (idPartner is > 0 &&
            _partnerIndex.TryGetValue(PartnerKey(idService, assignDirection, idPartner.Value), out var pt))
            return pt[0];

        return null;
    }

    private static string RouteKey(int idService, int dir, int route) => $"{idService}/{dir}/{route}";
    private static string PartnerKey(int idService, int dir, int idPartner) => $"{idService}/{dir}/{idPartner}";

    private static void Add(Dictionary<string, List<RatePlanAssignmentTuple>> index, string key, RatePlanAssignmentTuple t)
    {
        if (!index.TryGetValue(key, out var list)) index[key] = list = new List<RatePlanAssignmentTuple>();
        list.Add(t);
    }

    private static IReadOnlyDictionary<string, IReadOnlyList<RatePlanAssignmentTuple>> Freeze(
        Dictionary<string, List<RatePlanAssignmentTuple>> index)
    {
        var frozen = new Dictionary<string, IReadOnlyList<RatePlanAssignmentTuple>>(index.Count);
        foreach (var (key, list) in index)
            frozen[key] = list.OrderBy(t => t.Priority).ToList();   // lowest priority first
        return frozen;
    }

    public static RatePlanResolver Empty { get; } = Build([]);
}
