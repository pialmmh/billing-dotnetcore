using Billing.Config.TenantConfigSync.Internal.Dto;
using Billing.Config.TenantConfigSync.Model;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using Billing.Mediation.Validation;
using MediationModel;

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
        // PrefixMatcher longest-prefixes over the RateCache at charge time. The SG configs + checklists carry
        // rating-rule DATA and validation-rule REFERENCES — the references are bound to behaviour here.
        var sgConfigs = dto.ServiceGroupConfigurations?.ToDictionary(kv => kv.Key, kv => ToSgConfig(kv.Value));
        return MediationContext.ForRating(
            dto.RatePlanAssignmentTuples ?? [],
            dto.Categories,
            dto.ServiceGroupRules,
            sgConfigs,                            // null → the built-in default SG configs
            ToChecklist(dto.CommonChecklist));
    }

    private static ServiceGroupConfiguration ToSgConfig(ServiceGroupConfigDto dto) => new()
    {
        ServiceGroupId = dto.ServiceGroupId,
        Disabled = dto.Disabled,
        Rules = dto.Rules?.Select(r => (Rule)new RatingRule
        {
            IdServiceFamily = r.IdServiceFamily,
            AssignDirection = r.AssignDirection,
            DigitRulesData = r.DigitRulesData,
        }).ToList() ?? [],
        AnsweredChecklist = ToChecklist(dto.AnsweredChecklist),
        UnansweredChecklist = ToChecklist(dto.UnansweredChecklist),
    };

    // Bind validation-rule REFERENCES (name + optional data) to behaviour via the registry; unknown name
    // throws at config-load (fail-fast), not at mediation time.
    private static IReadOnlyList<IValidationRule<cdr>> ToChecklist(List<RuleRefDto>? refs) =>
        refs is null
            ? []
            : refs.Select(r => ValidationRuleRegistry.Default.Resolve(r.Rule ?? "", r.Data)).ToList();
}
