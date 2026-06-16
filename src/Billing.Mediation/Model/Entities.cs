namespace Billing.Mediation.Model;

// Minimal rating-side domain entities. These are the immutable inputs the rater needs,
// deserialized from config-manager's per-tenant payload (the same data routesphere's
// DynamicContext carries). Field names mirror the C# reference / routesphere JSON keys so
// the cross-system mapping stays obvious. They are intentionally small — only the fields the
// rating slice consumes; more get added as the A2ZRater/Sg*/Sf* port lands, and unknown JSON
// keys are ignored on deserialize, so growth is non-breaking.

/// <summary>A partner: operator, reseller, customer, or supplier. Each reseller owns a DB.</summary>
public sealed record Partner
{
    public int IdPartner { get; init; }
    public string PartnerName { get; init; } = "";
    /// <summary>Parent partner in the reseller chain; null = admin/root.</summary>
    public int? IdParentPartner { get; init; }
    public string? PartnerType { get; init; }
    /// <summary>The reseller's own schema (admin DB or res_NNN).</summary>
    public string? DatabaseName { get; init; }
}

/// <summary>A rate plan groups today's rates that apply to a set of partners.</summary>
public sealed record RatePlan
{
    public int Id { get; init; }
    public string? Name { get; init; }
    public IReadOnlyList<int> PartnerIds { get; init; } = [];
}

/// <summary>One rate row: price per billing span for a (plan, prefix/zone).</summary>
public sealed record Rate
{
    public string? Prefix { get; init; }
    public string? Country { get; init; }
    public decimal RateValue { get; init; }
    public string? Uom { get; init; }
    public int BillingSpanSeconds { get; init; } = 60;
}

/// <summary>Assigns a rate plan to a partner in a direction (customer = charge-in, supplier = pay-out).</summary>
public sealed record RateAssign
{
    public int Id { get; init; }
    public int FromPartnerId { get; init; }
    public int ToPartnerId { get; init; }
    public int RatePlanId { get; init; }
    public string? Direction { get; init; }
}

/// <summary>A prepaid package account. Live balance lives in routesphere mem-ledger;
/// this carries only the config fields dotnet ranks eligibility by.</summary>
public sealed record PackageAccount
{
    public long Id { get; init; }
    public long IdPartner { get; init; }
    public string? Uom { get; init; }
    public int? OnSelectPriority { get; init; }
    public string? Status { get; init; }
}
