// Split from legacy Mediation/Validation/CdrValidationRules.cs.
package com.telcobright.billing.mediation.validation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import com.telcobright.billing.mediation.engine.models.cdr;

/**
 * Resolves a cdr validation rule by its registered NAME (+ optional threshold) — the type-safe replacement
 * for the legacy MEF rule container. Default is the built-in set; resolution is FAIL-FAST — an unknown rule
 * name throws at config-load, not at mediation time.
 */
public final class ValidationRuleRegistry {
    private final Map<String, Function<BigDecimal, IValidationRule<cdr>>> _factories;

    public ValidationRuleRegistry(Map<String, Function<BigDecimal, IValidationRule<cdr>>> factories) {
        _factories = factories;
    }

    public boolean Contains(String name) {
        return _factories.containsKey(name);
    }

    public IValidationRule<cdr> Resolve(String name) {
        return Resolve(name, null);
    }

    public IValidationRule<cdr> Resolve(String name, BigDecimal data) {
        Function<BigDecimal, IValidationRule<cdr>> factory = _factories.get(name);
        if (factory == null)
            throw new IllegalStateException(
                    "Unknown validation rule '" + name + "'. Registered: " + String.join(", ", _factories.keySet()));
        return factory.apply(data);
    }

    public static final ValidationRuleRegistry Default = new ValidationRuleRegistry(buildDefaultFactories());

    private static Map<String, Function<BigDecimal, IValidationRule<cdr>>> buildDefaultFactories() {
        Map<String, Function<BigDecimal, IValidationRule<cdr>>> m = new HashMap<>();
        m.put("DurationSecGtEq0", d -> new DurationSecGtEq0());
        m.put("ServiceGroupGt0", d -> new ServiceGroupGt0());
        m.put("InPartnerIdGt0", d -> new InPartnerIdGt0());
        m.put("OutPartnerIdGt0", d -> new OutPartnerIdGt0());
        m.put("SwitchIdGt0", d -> new SwitchIdGt0());
        m.put("IdCallGt0", d -> new IdCallGt0());
        m.put("AnsIdTermGt0", d -> new AnsIdTermGt0());
        m.put("AnsIdOrigGt0", d -> new AnsIdOrigGt0());
        m.put("IncomingRouteNotEmpty", d -> new IncomingRouteNotEmpty());
        m.put("OutgoingRouteNotEmpty", d -> new OutgoingRouteNotEmpty());
        m.put("MatchedPrefixCustomerNotEmpty", d -> new MatchedPrefixCustomerNotEmpty());
        m.put("MatchedPrefixSupplierNotEmpty", d -> new MatchedPrefixSupplierNotEmpty());
        m.put("CountryCodeNotEmpty", d -> new CountryCodeNotEmpty());
        m.put("OriginatingCalledNumberNotEmpty", d -> new OriginatingCalledNumberNotEmpty());
        m.put("UniqueBillIdNotEmpty", d -> new UniqueBillIdNotEmpty());
        m.put("EndTimeIsGtEqStartTime", d -> new EndTimeIsGtEqStartTime());
        m.put("ChargingStatus1WhenDurationGt0", d -> new ChargingStatus1WhenDurationGt0());

        // the DurationSec-gated "<field> must be > 0 at/above the threshold, else 0" family.
        m.put("Duration1Gt0", d -> new DurationGatedPositive("Duration1", c -> c.Duration1, d != null ? d : BigDecimal.ZERO));
        m.put("Duration2Gt0", d -> new DurationGatedPositive("Duration2", c -> c.Duration2, d != null ? d : BigDecimal.ZERO));
        m.put("RoundedDurationGt0", d -> new DurationGatedPositive("RoundedDuration", c -> c.RoundedDuration, d != null ? d : BigDecimal.ZERO));
        m.put("InPartnerCostGt0", d -> new DurationGatedPositive("InPartnerCost", c -> c.InPartnerCost, d != null ? d : BigDecimal.ZERO));
        m.put("OutPartnerCostGt0", d -> new DurationGatedPositive("OutPartnerCost", c -> c.OutPartnerCost, d != null ? d : BigDecimal.ZERO));
        m.put("CostIcxInGt0", d -> new DurationGatedPositive("CostIcxIn", c -> c.CostIcxIn, d != null ? d : BigDecimal.ZERO));
        m.put("BtrcRevShareTax1Gt0", d -> new DurationGatedPositive("Tax1", c -> c.Tax1, d != null ? d : BigDecimal.ZERO));
        m.put("BtrcRevShareTax2Gt0", d -> new DurationGatedPositive("Tax2", c -> c.Tax2, d != null ? d : BigDecimal.ZERO));
        return m;
    }
}
