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

        // The today's-rates map is built once and shared: it backs both the raw lookup on
        // DynamicContext and the longest-prefix RateCache folded into the MediationContext.
        var todaysRates = dto.RatePlanWiseTodaysRates?.ToDictionary(
            kv => kv.Key, kv => (IReadOnlyDictionary<string, Rate>)kv.Value)
            ?? new Dictionary<int, IReadOnlyDictionary<string, Rate>>();

        return new DynamicContext
        {
            Partners = dto.Partners ?? new Dictionary<int, Partner>(),
            RatePlans = dto.RatePlans ?? new Dictionary<int, RatePlan>(),
            RatePlanWiseTodaysRates = todaysRates,
            RateAssignsCustomer = dto.RateAssignsCustomer ?? [],
            RateAssignsSupplier = dto.RateAssignsSupplier ?? [],
            PartnerIdWisePackageAccounts = dto.PartnerIdWisePackageAccounts?.ToDictionary(
                kv => kv.Key, kv => (IReadOnlyList<PackageAccount>)kv.Value)
                ?? new Dictionary<long, IReadOnlyList<PackageAccount>>(),
            MediationContext = ToMediation(dto.MediationContext, todaysRates),
        };
    }

    private static MediationContext ToMediation(
        MediationContextDto? dto,
        IReadOnlyDictionary<int, IReadOnlyDictionary<string, Rate>> todaysRates)
    {
        // The today-only RateCache is built from the tenant's already-today-scoped rates (rates ride on
        // DynamicContext, not inside MediationContextDto — so it is built even when the dto is absent),
        // and stamped with today's date so the day-boundary refresher can detect a rollover.
        var rateCache = RateCache.Build(todaysRates, DateOnly.FromDateTime(DateTime.Today));

        if (dto is null) return new MediationContext { RateCache = rateCache };

        return new MediationContext
        {
            Categories = dto.Categories ?? new Dictionary<int, ServiceCategory>(),
            ServiceGroupRules = dto.ServiceGroupRules ?? [],
            RateCache = rateCache,
        };
    }
}
