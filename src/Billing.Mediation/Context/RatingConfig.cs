namespace Billing.Mediation.Context;

// The rating-side configuration types the MediationContext is built from.
// `category` (EnumServiceCategory) is the ONE id shared across routesphere and dotnet —
// it travels in rating results and is the join key between detection, rates, and packages.

/// <summary>EnumServiceCategory — the cross-system service-group id namespace.</summary>
public sealed record ServiceCategory
{
    public int Id { get; init; }
    public string Type { get; init; } = "";
}

/// <summary>One ordered predicate over a CDR/call → a service group. First match wins
/// (mirrors the C# Sg* logic). Kept minimal here; the full imperative rules port with the rater.</summary>
public sealed record ServiceGroupMatchRule
{
    public string? PartnerType { get; init; }
    public string? CalledPrefix { get; init; }
    public int? SwitchId { get; init; }
    public string? Route { get; init; }
}

/// <summary>A service group: detection rules + the category it resolves to.</summary>
public sealed record ServiceGroupDef
{
    public int Id { get; init; }
    public string Name { get; init; } = "";
    public int CategoryId { get; init; }
    public IReadOnlyList<ServiceGroupMatchRule> MatchRules { get; init; } = [];
}

/// <summary>A service family resolved within a (service group, direction).</summary>
public sealed record ServiceFamilyDef
{
    public int Id { get; init; }
    public string Name { get; init; } = "";
    public int ServiceGroupId { get; init; }
}
