// Ported from legacy Mediation/Model/Entities.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One rate row as config-manager serves it — the FULL rating-side today's-rates row. It is a pure Jackson
 * deserialization target for config-manager's JSON (camelCase, matched case-insensitively against these
 * PascalCase fields), read ONLY by {@code ConfigManagerMapper.ToEngineRate} to build the engine
 * {@code rate}.
 *
 * <p>It now carries EVERY column the rating engine reads — dates ({@code StartDate}/{@code EndDate}),
 * {@code Category}/{@code SubCategory}, the {@code BillingSpan} seconds override, {@code RateAmountRoundupDecimal}
 * and the {@code OtherAmount1..10} surcharge / fraction-ceiling knobs — not just the lean admission-side
 * subset it used to (the admission path uses the engine RateCache, not this DTO).</p>
 *
 * <p>A public-mutable-field class (mirroring the engine models) rather than a record: ~25 fields make a
 * record's positional constructor unwieldy, and instances are built only by Jackson (no-arg ctor + public
 * field injection) and by tests (field assignment).</p>
 */
public class Rate {
    public long Id;
    public String Prefix;
    public Integer IdRatePlan;
    public BigDecimal RateAmount;
    public String CountryCode;
    public Integer Category;
    public Integer SubCategory;
    public Integer Resolution;
    /** config-manager serves this as a float ({@code minDurationSec}); BigDecimal deserializes from it fine. */
    public BigDecimal MinDurationSec;
    public Integer SurchargeTime;
    public BigDecimal SurchargeAmount;
    public Integer Inactive;
    /** column {@code startdate}; null when unset (today's-rates are currently valid -> mapper uses MinDate). */
    public LocalDateTime StartDate;
    /** column {@code enddate}; null = open (no end). */
    public LocalDateTime EndDate;
    /** column {@code billingspan}: a seconds override; null = resolve via the rate plan's BillingSpan uom. */
    public Integer BillingSpan;
    public Integer RateAmountRoundupDecimal;
    public BigDecimal OtherAmount1;
    public BigDecimal OtherAmount2;
    public BigDecimal OtherAmount3;
    public BigDecimal OtherAmount4;
    public BigDecimal OtherAmount5;
    public BigDecimal OtherAmount6;
    public Float OtherAmount7;
    public Float OtherAmount8;
    public Float OtherAmount9;
    public Float OtherAmount10;
}
