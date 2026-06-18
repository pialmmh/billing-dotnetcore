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
}
