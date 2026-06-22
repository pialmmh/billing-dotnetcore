using MediationModel;

namespace Billing.Mediation.Validation;

// The built-in cdr validation rules (ported from legacy CdrValidationRules) and the registry that resolves
// them BY NAME. Replaces the legacy MEF [Export]/[ImportMany] + Spring.NET composition: BEHAVIOUR lives here
// in versioned code; POLICY (which rules apply, with what threshold) is pure data in config-manager's JSON
// ({ "rule": "InPartnerCostGt0", "data": 1 }). The mapper binds the two via ValidationRuleRegistry.

// ---- presence / positivity rules (no data) ----

/// <summary>The answered/billable seconds must be non-negative.</summary>
public sealed class DurationSecGtEq0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.DurationSec >= 0;
    public string ValidationMessage => "DurationSec must be >= 0";
}

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

public sealed class SwitchIdGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.SwitchId > 0;
    public string ValidationMessage => "SwitchId must be > 0";
}

public sealed class IdCallGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.IdCall > 0;
    public string ValidationMessage => "IdCall must be > 0";
}

public sealed class AnsIdTermGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.AnsIdTerm is > 0;
    public string ValidationMessage => "AnsIdTerm must be > 0";
}

public sealed class AnsIdOrigGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.AnsIdOrig is > 0;
    public string ValidationMessage => "AnsIdOrig must be > 0";
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

public sealed class OriginatingCalledNumberNotEmpty : IValidationRule<cdr>
{
    public bool Validate(cdr c) => !string.IsNullOrEmpty(c.OriginatingCalledNumber);
    public string ValidationMessage => "OriginatingCalledNumber must not be empty";
}

public sealed class UniqueBillIdNotEmpty : IValidationRule<cdr>
{
    public bool Validate(cdr c) => !string.IsNullOrEmpty(c.UniqueBillId);
    public string ValidationMessage => "UniqueBillId must not be empty";
}

public sealed class EndTimeIsGtEqStartTime : IValidationRule<cdr>
{
    public bool Validate(cdr c) => c.EndTime >= c.StartTime;
    public string ValidationMessage => "EndTime must be >= StartTime";
}

/// <summary>An answered (DurationSec &gt; 0) call must have ChargingStatus 1; otherwise 0 or 1 is allowed.</summary>
public sealed class ChargingStatus1WhenDurationGt0 : IValidationRule<cdr>
{
    public bool Validate(cdr c) =>
        c.DurationSec > 0 ? c.ChargingStatus == 1 : c.ChargingStatus is 0 or 1;
    public string ValidationMessage => "ChargingStatus must be 1 when DurationSec > 0";
}

/// <summary>
/// The legacy "&lt;field&gt;Gt0 {Data = minDurationSec}" family: when <c>DurationSec &gt;= Data</c> the field
/// must be &gt; 0, otherwise it must be 0 (no charge/metric for short/zero-duration calls). One class
/// parameterised by field; the registry names each variant (Duration2Gt0, InPartnerCostGt0, CostIcxInGt0, …).
/// </summary>
public sealed class DurationGatedPositive : IValidationRule<cdr>
{
    private readonly string _field;
    private readonly Func<cdr, decimal?> _value;
    private readonly decimal _minDurationSec;

    public DurationGatedPositive(string field, Func<cdr, decimal?> value, decimal minDurationSec)
    {
        _field = field;
        _value = value;
        _minDurationSec = minDurationSec;
    }

    public bool Validate(cdr c)
    {
        var v = _value(c) ?? 0m;
        return c.DurationSec >= _minDurationSec ? v > 0m : v == 0m;
    }

    public string ValidationMessage => $"{_field} must be > 0 when DurationSec >= {_minDurationSec} (else 0)";
}

/// <summary>
/// Resolves a cdr validation rule by its registered NAME (+ optional threshold) — the type-safe replacement
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
            ["SwitchIdGt0"] = _ => new SwitchIdGt0(),
            ["IdCallGt0"] = _ => new IdCallGt0(),
            ["AnsIdTermGt0"] = _ => new AnsIdTermGt0(),
            ["AnsIdOrigGt0"] = _ => new AnsIdOrigGt0(),
            ["IncomingRouteNotEmpty"] = _ => new IncomingRouteNotEmpty(),
            ["OutgoingRouteNotEmpty"] = _ => new OutgoingRouteNotEmpty(),
            ["MatchedPrefixCustomerNotEmpty"] = _ => new MatchedPrefixCustomerNotEmpty(),
            ["MatchedPrefixSupplierNotEmpty"] = _ => new MatchedPrefixSupplierNotEmpty(),
            ["CountryCodeNotEmpty"] = _ => new CountryCodeNotEmpty(),
            ["OriginatingCalledNumberNotEmpty"] = _ => new OriginatingCalledNumberNotEmpty(),
            ["UniqueBillIdNotEmpty"] = _ => new UniqueBillIdNotEmpty(),
            ["EndTimeIsGtEqStartTime"] = _ => new EndTimeIsGtEqStartTime(),
            ["ChargingStatus1WhenDurationGt0"] = _ => new ChargingStatus1WhenDurationGt0(),

            // the DurationSec-gated "<field> must be > 0 at/above the threshold, else 0" family.
            ["Duration1Gt0"] = d => new DurationGatedPositive("Duration1", c => c.Duration1, d ?? 0m),
            ["Duration2Gt0"] = d => new DurationGatedPositive("Duration2", c => c.Duration2, d ?? 0m),
            ["RoundedDurationGt0"] = d => new DurationGatedPositive("RoundedDuration", c => c.RoundedDuration, d ?? 0m),
            ["InPartnerCostGt0"] = d => new DurationGatedPositive("InPartnerCost", c => c.InPartnerCost, d ?? 0m),
            ["OutPartnerCostGt0"] = d => new DurationGatedPositive("OutPartnerCost", c => c.OutPartnerCost, d ?? 0m),
            ["CostIcxInGt0"] = d => new DurationGatedPositive("CostIcxIn", c => c.CostIcxIn, d ?? 0m),
            ["BtrcRevShareTax1Gt0"] = d => new DurationGatedPositive("Tax1", c => c.Tax1, d ?? 0m),
            ["BtrcRevShareTax2Gt0"] = d => new DurationGatedPositive("Tax2", c => c.Tax2, d ?? 0m),
        });
}
