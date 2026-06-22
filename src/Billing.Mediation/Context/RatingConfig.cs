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

/// <summary>
/// The reusable base for a service group's configured rules — "just a rule". A service group holds an
/// ordered list of these (<see cref="ServiceGroupConfiguration.Rules"/>); each concrete kind is a rule the
/// mediation engine knows how to run. <see cref="RatingRule"/> is the only kind today; partner rules (and
/// others) slot in as further <c>Rule</c> subtypes without changing the configuration shape.
/// </summary>
public abstract record Rule;

/// <summary>
/// A RATING rule (legacy <c>RatingRule</c>): which service family to run, in which assignment direction,
/// with which digit rules. The engine iterates a service group's rules in order, resolving each family by
/// <see cref="IdServiceFamily"/> and charging the leg for <see cref="AssignDirection"/>.
/// <see cref="DigitRulesData"/> is carried for fidelity (the per-rule digit grouping) but not yet applied.
/// </summary>
public sealed record RatingRule : Rule
{
    public int IdServiceFamily { get; init; }
    public int AssignDirection { get; init; }   // legacy: 1=Customer, 2=Supplier, 0=None
    public string? DigitRulesData { get; init; }
}

/// <summary>
/// A service group's configuration (legacy <c>ServiceGroupConfiguration</c>, from
/// <c>CdrSetting.ServiceGroupConfigurations</c>): the ordered <see cref="Rules"/> run for a detected SG
/// (rating rules today, partner rules later) and whether the SG is <see cref="Disabled"/>. Served by
/// config-manager; <see cref="Defaults"/> is the built-in fallback until it does.
/// </summary>
public sealed record ServiceGroupConfiguration
{
    public int ServiceGroupId { get; init; }
    public bool Disabled { get; init; }
    public IReadOnlyList<Rule> Rules { get; init; } = [];

    /// <summary>The built-in default configs (mirror the previously-hardcoded family map), overridden by
    /// config-manager: SG10 → SF10 customer + SF1 supplier; SG11 → SF11 customer.</summary>
    public static IReadOnlyDictionary<int, ServiceGroupConfiguration> Defaults { get; } =
        new Dictionary<int, ServiceGroupConfiguration>
        {
            [10] = new()
            {
                ServiceGroupId = 10,
                Rules = new Rule[]
                {
                    new RatingRule { IdServiceFamily = 10, AssignDirection = 1 },   // SF10 customer (A2Z + VAT)
                    new RatingRule { IdServiceFamily = 1, AssignDirection = 2 },    // SF1 supplier (base A2Z cost)
                },
            },
            [11] = new()
            {
                ServiceGroupId = 11,
                Rules = new Rule[]
                {
                    new RatingRule { IdServiceFamily = 11, AssignDirection = 1 },   // SF11 customer (dom off-net in)
                },
            },
        };
}
