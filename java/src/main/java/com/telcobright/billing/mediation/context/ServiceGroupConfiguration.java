// Ported from legacy Mediation/Context/RatingConfig.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.context;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.validation.DurationSecGtEq0;
import com.telcobright.billing.mediation.validation.EndTimeIsGtEqStartTime;
import com.telcobright.billing.mediation.validation.IValidationRule;
import com.telcobright.billing.mediation.validation.InPartnerIdGt0;

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

    /** SG 30 — an ad view (stated by the producer, pre-rated by the switch; see {@code SgAdView}). */
    public static final int AdViewServiceGroup = 30;

    /**
     * The built-in default configs (mirror the previously-hardcoded family map), overridden by
     * config-manager: SG10 -> SF10 customer + SF1 supplier; SG11 -> SF11 customer; SG30 -> SF30 customer
     * (pre-rated: one chargeable from the record's settled amounts) with its two checklists.
     *
     * <p>SG30's checklists (brief B4). ANSWERED (the view was shown): an in-partner, a duration that is not
     * negative, an end that is not before the start. UNANSWERED (never shown, or refused): an in-partner, an end
     * that is not before the start. The switch always names an in-partner — the payer, else the tenant's own
     * operator partner — so a record without one is not a view anybody can be billed for: it goes to cdrerror.
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
                    List.of(), List.of()),
            AdViewServiceGroup, new ServiceGroupConfiguration(AdViewServiceGroup, false,
                    List.<Rule>of(
                            new RatingRule(30, 1, null)),   // SF30 customer (pre-rated)
                    List.of(new InPartnerIdGt0(), new DurationSecGtEq0(), new EndTimeIsGtEqStartTime()),
                    List.of(new InPartnerIdGt0(), new EndTimeIsGtEqStartTime()))
    );

    /**
     * The tenant's configurations, with SG30's built-in one where the tenant serves none. A served map REPLACES
     * the defaults wholesale for SG10 / SG11 (a tenant that serves no SG10 has no SG10 — unchanged); SG30 alone is
     * always there, because no detector can turn a stated ad view into anything else: without a configuration it
     * would be written unchecked. A served SG30 wins over the built-in one.
     */
    public static Map<Integer, ServiceGroupConfiguration> WithTheBuiltInAdView(Map<Integer, ServiceGroupConfiguration> served) {
        if (served.containsKey(AdViewServiceGroup)) return served;
        Map<Integer, ServiceGroupConfiguration> all = new java.util.HashMap<>(served);
        all.put(AdViewServiceGroup, Defaults.get(AdViewServiceGroup));
        return all;
    }
}
