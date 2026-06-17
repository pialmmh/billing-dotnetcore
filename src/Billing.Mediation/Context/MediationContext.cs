namespace Billing.Mediation.Context;

/// <summary>
/// The rating-side config for one tenant. It is folded INSIDE that tenant's
/// <c>DynamicContext</c> (see Billing.Config) so there is exactly one config snapshot per
/// tenant: <c>tenant.Context.MediationContext</c>. Immutable; swapped wholesale on reload.
///
/// Holds the category lookup (the cross-system id namespace) and the ordered service-group detection
/// rules. config-manager serves <see cref="Categories"/> from the existing EnumServiceCategory table;
/// <see cref="ServiceGroupRules"/> arrives once its (additive) table is ratified — empty until then.
/// </summary>
public sealed class MediationContext
{
    public IReadOnlyDictionary<int, ServiceCategory> Categories { get; init; }
        = new Dictionary<int, ServiceCategory>();

    public IReadOnlyList<ServiceGroupRule> ServiceGroupRules { get; init; } = [];

    /// <summary>An empty context — the safe default before the first successful load.</summary>
    public static MediationContext Empty { get; } = new();
}
