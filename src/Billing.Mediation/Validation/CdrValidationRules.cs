using MediationModel;

namespace Billing.Mediation.Validation;

// The built-in cdr validation rules (ported from legacy CdrValidationRules.CommonCdrValidationRules) and the
// registry that resolves them BY NAME. This replaces the legacy MEF [Export]/[ImportMany] + Spring.NET
// composition: BEHAVIOUR lives here in versioned code; POLICY (which rules apply, with what threshold) is
// pure data in config-manager's JSON ({ "rule": "InPartnerIdGt0", "data": 0.1 }). The mapper binds the two.

/// <summary>The answered/billable seconds must be non-negative.</summary>
public sealed class DurationSecGtEq0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.DurationSec >= 0;
    public string ValidationMessage => "DurationSec must be >= 0";
}

/// <summary>A service group must have been detected.</summary>
public sealed class ServiceGroupGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.ServiceGroup > 0;
    public string ValidationMessage => "ServiceGroup must be > 0";
}

public sealed class InPartnerIdGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.InPartnerId is > 0;
    public string ValidationMessage => "InPartnerId must be > 0";
}

public sealed class OutPartnerIdGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.OutPartnerId is > 0;
    public string ValidationMessage => "OutPartnerId must be > 0";
}

public sealed class IncomingRouteNotEmpty : IValidationRule<cdr>
{
    public bool Validate(cdr c) => !string.IsNullOrEmpty(c.IncomingRoute);
    public string ValidationMessage => "IncomingRoute must not be empty";
}

public sealed class OutgoingRouteNotEmpty : IValidationRule<cdr>
{
    public bool Validate(cdr c) => !string.IsNullOrEmpty(c.OutgoingRoute);
    public string ValidationMessage => "OutgoingRoute must not be empty";
}

public sealed class MatchedPrefixCustomerNotEmpty : IValidationRule<cdr>
{
    public bool Validate(cdr c) => !string.IsNullOrEmpty(c.MatchedPrefixCustomer);
    public string ValidationMessage => "MatchedPrefixCustomer must not be empty";
}

public sealed class MatchedPrefixSupplierNotEmpty : IValidationRule<cdr>
{
    public bool Validate(cdr c) => !string.IsNullOrEmpty(c.MatchedPrefixSupplier);
    public string ValidationMessage => "MatchedPrefixSupplier must not be empty";
}

public sealed class CountryCodeNotEmpty : IValidationRule<cdr>
{
    public bool Validate(cdr c) => !string.IsNullOrEmpty(c.CountryCode);
    public string ValidationMessage => "CountryCode must not be empty";
}

/// <summary>The in-partner cost must exceed a configured threshold (legacy InPartnerCostGt0 with Data) — an
/// example of a DATA-parameterised rule.</summary>
public sealed class InPartnerCostGt : IValidationRule<cdr>
{
    private readonly decimal _min;
    public InPartnerCostGt(decimal min) => _min = min;
    public bool Validate(cdr c) => (c.InPartnerCost ?? 0m) > _min;
    public string ValidationMessage => $"InPartnerCost must be > {_min}";
}

/// <summary>
/// Resolves a cdr validation rule by its registered NAME (+ optional threshold), the type-safe replacement
/// for the legacy MEF rule container. <see cref="Default"/> is the built-in set; resolution is FAIL-FAST —
/// an unknown rule name throws at config-load, not at mediation time.
/// </summary>
public sealed class ValidationRuleRegistry
{
    private readonly IReadOnlyDictionary<string, Func<decimal?, IValidationRule<cdr>>> _factories;

    public ValidationRuleRegistry(IReadOnlyDictionary<string, Func<decimal?, IValidationRule<cdr>>> factories) =>
        _factories = factories;

    public bool Contains(string name) => _factories.ContainsKey(name);

    public IValidationRule<cdr> Resolve(string name, decimal? data = null)
    {
        if (!_factories.TryGetValue(name, out var factory))
            throw new InvalidOperationException(
                $"Unknown validation rule '{name}'. Registered: {string.Join(", ", _factories.Keys)}");
        return factory(data);
    }

    public static ValidationRuleRegistry Default { get; } = new(
        new Dictionary<string, Func<decimal?, IValidationRule<cdr>>>
        {
            ["DurationSecGtEq0"] = _ => new DurationSecGtEq0(),
            ["ServiceGroupGt0"] = _ => new ServiceGroupGt0(),
            ["InPartnerIdGt0"] = _ => new InPartnerIdGt0(),
            ["OutPartnerIdGt0"] = _ => new OutPartnerIdGt0(),
            ["IncomingRouteNotEmpty"] = _ => new IncomingRouteNotEmpty(),
            ["OutgoingRouteNotEmpty"] = _ => new OutgoingRouteNotEmpty(),
            ["MatchedPrefixCustomerNotEmpty"] = _ => new MatchedPrefixCustomerNotEmpty(),
            ["MatchedPrefixSupplierNotEmpty"] = _ => new MatchedPrefixSupplierNotEmpty(),
            ["CountryCodeNotEmpty"] = _ => new CountryCodeNotEmpty(),
            ["InPartnerCostGt"] = d => new InPartnerCostGt(d ?? 0m),
        });
}
