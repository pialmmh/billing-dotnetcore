// Ported from legacy Mediation/Model/Entities.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.model;

import java.util.List;

/**
 * A rate plan as config-manager serves it. A pure Jackson deserialization target for config-manager's JSON
 * (camelCase, matched case-insensitively against these PascalCase fields), read ONLY by
 * {@code ConfigManagerMapper.ToDicRatePlan} to build the engine {@code rateplan}.
 *
 * <p>It now carries the rating-side knobs the engine reads off the plan — {@code Field4} (tech prefix),
 * {@code BillingSpan} (the uom string resolved to seconds via the served billingSpans) and
 * {@code RateAmountRoundupDecimal} — alongside the existing {@code Id}/{@code Name}/{@code PartnerIds}.</p>
 *
 * <p>{@code Name} is left as-is: it does NOT match config-manager's {@code ratePlanName} (so it stays null
 * after deserialization) and is unused by rating. A public-mutable-field class (mirroring {@link Rate} and
 * the engine models): instances are built only by Jackson and by tests.</p>
 */
public class RatePlan {
    public int Id;
    public String Name;
    public List<Integer> PartnerIds = List.of();
    public String Field4;
    public String BillingSpan;
    public Integer RateAmountRoundupDecimal;
    /** Billed currency uom (served {@code currency}, e.g. "BDT") → engine {@code rateplan.Currency} →
     *  {@code acc_chargeable.idBilledUom}, which the live schema keeps NOT NULL — without it every
     *  chargeable INSERT fails. */
    public String Currency;
}
