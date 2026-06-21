using Billing.Config.TenantConfigSync.Internal.Dto;
using Billing.Config.TenantConfigSync.Model;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>Builds the immutable Model (Tenant tree + DynamicContext + MediationContext) from the
/// config-manager wire DTOs. One reason to change: the wire shape. Computed lookups (index, ancestor
/// chains) are added afterwards by <see cref="TenantTreeBuilder"/>.</summary>
internal static class ConfigManagerMapper
{
    public static Tenant ToTenant(TenantDto dto) => new()
    {
        Name = dto.Name ?? "",
        DbName = dto.DbName ?? "",
        Parent = dto.Parent,
        Children = dto.Children is null
            ? new Dictionary<string, Tenant>()
            : dto.Children.ToDictionary(kv => kv.Key, kv => ToTenant(kv.Value)),
        Context = ToContext(dto.Context),
    };

    private static DynamicContext ToContext(DynamicContextDto? dto)
    {
        if (dto is null) return DynamicContext.Empty;
        return new DynamicContext
        {
            Partners = dto.Partners ?? new Dictionary<int, Partner>(),
            RatePlans = dto.RatePlans ?? new Dictionary<int, RatePlan>(),
            RatePlanWiseTodaysRates = dto.RatePlanWiseTodaysRates?.ToDictionary(
                kv => kv.Key, kv => (IReadOnlyDictionary<string, Rate>)kv.Value)
                ?? new Dictionary<int, IReadOnlyDictionary<string, Rate>>(),
            RateAssignsCustomer = dto.RateAssignsCustomer ?? [],
            RateAssignsSupplier = dto.RateAssignsSupplier ?? [],
            PartnerIdWisePackageAccounts = dto.PartnerIdWisePackageAccounts?.ToDictionary(
                kv => kv.Key, kv => (IReadOnlyList<PackageAccount>)kv.Value)
                ?? new Dictionary<long, IReadOnlyList<PackageAccount>>(),
            MediationContext = ToMediation(dto.MediationContext),
        };
    }

    private static MediationContext ToMediation(MediationContextDto? dto)
    {
        if (dto is null) return MediationContext.Empty;
        // The resolver (which tuples apply) and the per-day RateCache (their rates) are both derived from the
        // tenant's verbatim legacy rateplanassignmenttuples (each carrying its rateassigns); the legacy
        // PrefixMatcher longest-prefixes over the RateCache at charge time.
        return MediationContext.ForRating(
            dto.RatePlanAssignmentTuples ?? [],
            dto.Categories,
            dto.ServiceGroupRules,
            dto.ServiceGroupConfigurations);   // null → the built-in default SG configs
    }
}
