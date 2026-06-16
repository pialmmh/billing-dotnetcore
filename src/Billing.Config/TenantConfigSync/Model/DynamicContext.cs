using Billing.Mediation.Context;
using Billing.Mediation.Model;

namespace Billing.Config.TenantConfigSync.Model;

/// <summary>
/// One tenant's immutable config snapshot — the .NET mirror of routesphere's
/// <c>DynamicContext</c>. Per the design, the rating brain rides inside it as
/// <see cref="MediationContext"/>, so resolving a tenant gives you everything:
/// <c>tenant.Context.MediationContext</c>.
///
/// Carries the billing-relevant maps (partners, rate plans, today's rates, rate
/// assignments, package accounts). Other routesphere maps (dialplan/DID/SMS) are omitted —
/// this service rates, it does not route. The whole object is replaced on each reload.
/// </summary>
public sealed class DynamicContext
{
    /// <summary>The rating brain for this tenant (folded in, per design §4a).</summary>
    public MediationContext MediationContext { get; init; } = MediationContext.Empty;

    public IReadOnlyDictionary<int, Partner> Partners { get; init; }
        = new Dictionary<int, Partner>();

    public IReadOnlyDictionary<int, RatePlan> RatePlans { get; init; }
        = new Dictionary<int, RatePlan>();

    /// <summary>plan id → (prefix/zone → rate). Mirrors routesphere ratePlanWiseTodaysRates.</summary>
    public IReadOnlyDictionary<int, IReadOnlyDictionary<string, Rate>> RatePlanWiseTodaysRates { get; init; }
        = new Dictionary<int, IReadOnlyDictionary<string, Rate>>();

    public IReadOnlyList<RateAssign> RateAssignsCustomer { get; init; } = [];

    public IReadOnlyList<RateAssign> RateAssignsSupplier { get; init; } = [];

    /// <summary>partner id → package accounts, ranked (ACTIVE, by onSelectPriority). Eligibility only.</summary>
    public IReadOnlyDictionary<long, IReadOnlyList<PackageAccount>> PartnerIdWisePackageAccounts { get; init; }
        = new Dictionary<long, IReadOnlyList<PackageAccount>>();

    /// <summary>The safe default before a tenant's first successful load.</summary>
    public static DynamicContext Empty { get; } = new();
}
