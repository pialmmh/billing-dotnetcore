namespace Billing.Mediation.Context;

/// <summary>
/// The rating-side config for one tenant. It is folded INSIDE that tenant's
/// <c>DynamicContext</c> (see Billing.Config) so there is exactly one config snapshot per
/// tenant: <c>tenant.Context.MediationContext</c>. Immutable; swapped wholesale on reload.
///
/// Holds the service-group detection table (→ category), the service families, and the
/// category lookup. The imperative Sg*/Sf* rules and the rate/longest-prefix tables port
/// here as the rater lands; for now this carries the shape and the cross-system category id.
/// </summary>
public sealed class MediationContext
{
    public IReadOnlyDictionary<int, ServiceCategory> Categories { get; init; }
        = new Dictionary<int, ServiceCategory>();

    public IReadOnlyList<ServiceGroupDef> ServiceGroups { get; init; } = [];

    public IReadOnlyList<ServiceFamilyDef> ServiceFamilies { get; init; } = [];

    /// <summary>An empty context — the safe default before the first successful load.</summary>
    public static MediationContext Empty { get; } = new();
}
