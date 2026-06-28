using Billing.Mediation.Context;
using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Config.TenantConfigSync.Internal.Dto;

// Wire shapes for config-manager's /get-specific-tenant-root response. Plain mutable graphs so
// System.Text.Json maps cleanly; the immutable Model is built from these (the routesphere pattern:
// deserialize, then construct the immutable snapshot). JSON keys match routesphere's payload.

internal sealed class TenantDto
{
    public string? Name { get; set; }
    public string? DbName { get; set; }
    public string? Parent { get; set; }
    public Dictionary<string, TenantDto>? Children { get; set; }
    public DynamicContextDto? Context { get; set; }
}

internal sealed class DynamicContextDto
{
    public Dictionary<int, Partner>? Partners { get; set; }
    public Dictionary<int, RatePlan>? RatePlans { get; set; }
    public Dictionary<int, Dictionary<string, Rate>>? RatePlanWiseTodaysRates { get; set; }
    public List<RateAssign>? RateAssignsCustomer { get; set; }
    public List<RateAssign>? RateAssignsSupplier { get; set; }
    public Dictionary<long, List<PackageAccount>>? PartnerIdWisePackageAccounts { get; set; }

    /// <summary>The rating-side config folded in. May be absent until config-manager serves it
    /// (open item: the shared EnumServiceCategory namespace) — then MediationContext stays Empty.</summary>
    public MediationContextDto? MediationContext { get; set; }
}

internal sealed class MediationContextDto
{
    public Dictionary<int, ServiceCategory>? Categories { get; set; }
    public List<ServiceGroupRule>? ServiceGroupRules { get; set; }

    /// <summary>The verbatim legacy rate-plan-assignment tuples (idService + AssignDirection +
    /// idpartner/route + priority), each carrying its nested rateassigns. config-manager serves this
    /// legacy-shaped JSON from the existing rateplanassignmenttuple/rateassign tables. Absent until it
    /// does, leaving the resolver empty.</summary>
    public List<rateplanassignmenttuple>? RatePlanAssignmentTuples { get; set; }

    /// <summary>Per-service-group configuration (rating rules + validation checklists). Absent until
    /// config-manager serves it, in which case the built-in defaults apply.</summary>
    public Dictionary<int, ServiceGroupConfigDto>? ServiceGroupConfigurations { get; set; }

    /// <summary>The common (all-cdr) validation checklist, as rule references.</summary>
    public List<RuleRefDto>? CommonChecklist { get; set; }
}

/// <summary>One service group's wire config: the rating rules and the two validation checklists, the latter
/// as rule REFERENCES (name + optional threshold) — behaviour is NOT serialized, it is resolved from the
/// ValidationRuleRegistry. (Partner rules will join <see cref="Rules"/> as another rule kind later.)</summary>
internal sealed class ServiceGroupConfigDto
{
    public int ServiceGroupId { get; set; }
    public bool Disabled { get; set; }
    public List<RatingRuleDto>? Rules { get; set; }
    public List<RuleRefDto>? AnsweredChecklist { get; set; }
    public List<RuleRefDto>? UnansweredChecklist { get; set; }
}

/// <summary>A rating rule on the wire — pure data (family id + direction + digit rules).</summary>
internal sealed class RatingRuleDto
{
    public int IdServiceFamily { get; set; }
    public int AssignDirection { get; set; }
    public string? DigitRulesData { get; set; }
}

/// <summary>A validation-rule reference: the registered rule <see cref="Rule"/> name + an optional
/// <see cref="Data"/> threshold. No .NET type names, no polymorphic deserialization.</summary>
internal sealed class RuleRefDto
{
    public string? Rule { get; set; }
    public decimal? Data { get; set; }
}
