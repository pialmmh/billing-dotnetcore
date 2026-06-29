// Ported from legacy Mediation/Context/RatingConfig.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.context;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.validation.IValidationRule;

import java.util.List;
import java.util.Map;

/**
 * A service group's configuration (legacy {@code ServiceGroupConfiguration}, from
 * {@code CdrSetting.ServiceGroupConfigurations}): the ordered {@code Rules} run for a detected SG (rating
 * rules today, partner rules later) and whether the SG is {@code Disabled}. Served by config-manager;
 * {@code Defaults} is the built-in fallback until it does.
 *
 * <p>FAITHFUL-PORT NOTE: positional Java record (components in C# declaration order) where the C# used an
 * object initializer. The C# {@code = []} defaults on the three list components are preserved via the compact
 * constructor (null normalises to an empty list).</p>
 */
public record ServiceGroupConfiguration(
        int ServiceGroupId,
        boolean Disabled,
        List<Rule> Rules,
        /** Post-mediation qualification checklist for CHARGEABLE (answered) calls of this SG. */
        List<IValidationRule<cdr>> AnsweredChecklist,
        /** Post-mediation qualification checklist for FAILED (unanswered) calls of this SG. */
        List<IValidationRule<cdr>> UnansweredChecklist
) {
    public ServiceGroupConfiguration {
        if (Rules == null) Rules = List.of();
        if (AnsweredChecklist == null) AnsweredChecklist = List.of();
        if (UnansweredChecklist == null) UnansweredChecklist = List.of();
    }

    /**
     * The built-in default configs (mirror the previously-hardcoded family map), overridden by
     * config-manager: SG10 -> SF10 customer + SF1 supplier; SG11 -> SF11 customer.
     */
    public static final Map<Integer, ServiceGroupConfiguration> Defaults = Map.of(
            10, new ServiceGroupConfiguration(10, false,
                    // explicit <Rule> witness: C# `new Rule[]{...}` is covariant; Java List<RatingRule> is not a List<Rule>.
                    List.<Rule>of(
                            new RatingRule(10, 1, null),   // SF10 customer (A2Z + VAT)
                            new RatingRule(1, 2, null)),    // SF1 supplier (base A2Z cost)
                    List.of(), List.of()),
            11, new ServiceGroupConfiguration(11, false,
                    List.<Rule>of(
                            new RatingRule(11, 1, null)),   // SF11 customer (dom off-net in)
                    List.of(), List.of())
    );
}
