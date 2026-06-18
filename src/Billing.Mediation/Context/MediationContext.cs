using Billing.Mediation.Rating;

namespace Billing.Mediation.Context;

/// <summary>
/// The rating-side config for one tenant. It is folded INSIDE that tenant's
/// <c>DynamicContext</c> (see Billing.Config) so there is exactly one config snapshot per
/// tenant: <c>tenant.Context.MediationContext</c>. Immutable; swapped wholesale on reload.
///
/// Holds the category lookup (the cross-system id namespace), the ordered service-group detection
/// rules, and the today-only <see cref="RateCache"/> (the rater's prefix→Rate lookup). config-manager
/// serves <see cref="Categories"/> from the existing EnumServiceCategory table;
/// <see cref="ServiceGroupRules"/> arrives once its (additive) table is ratified — empty until then.
/// </summary>
public sealed class MediationContext
{
    public IReadOnlyDictionary<int, ServiceCategory> Categories { get; init; }
        = new Dictionary<int, ServiceCategory>();

    public IReadOnlyList<ServiceGroupRule> ServiceGroupRules { get; init; } = [];

    /// <summary>This tenant's today-valid rate lookup, built from the tenant's
    /// <c>RatePlanWiseTodaysRates</c>. Stamped with the date it was built for so the day-boundary
    /// refresher can detect a rollover and rebuild (CDC config-events do not fire at midnight).</summary>
    public RateCache RateCache { get; init; } = RateCache.Empty;

    /// <summary>An empty context — the safe default before the first successful load.</summary>
    public static MediationContext Empty { get; } = new();
}
