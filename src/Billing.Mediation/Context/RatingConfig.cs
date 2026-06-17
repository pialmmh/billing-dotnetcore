namespace Billing.Mediation.Context;

// The rating-side configuration types the MediationContext is built from, served by config-manager
// folded inside DynamicContext. `category` (EnumServiceCategory) is the ONE id shared across
// routesphere and dotnet — it travels in rating results and joins detection, rates, and packages.

/// <summary>EnumServiceCategory — the cross-system service-group id namespace (config-manager table).</summary>
public sealed record ServiceCategory
{
    public int Id { get; init; }
    public string Type { get; init; } = "";
}

/// <summary>
/// One service-group DETECTION rule: a predicate over the call (partnerType + called-prefix, optionally
/// switch/route) → a category. Ordered by priority, first match wins (mirrors the C# Sg* logic).
/// Each row is one rule, so it maps to one small config-manager table.
/// </summary>
public sealed record ServiceGroupRule
{
    public int Id { get; init; }
    public string Name { get; init; } = "";
    public int? PartnerType { get; init; }
    public string? CalledPrefix { get; init; }
    public int? SwitchId { get; init; }
    public int CategoryId { get; init; }
    public int Priority { get; init; }
}
