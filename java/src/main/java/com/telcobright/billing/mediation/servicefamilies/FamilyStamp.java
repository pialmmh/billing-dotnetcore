package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRateResult;
import com.telcobright.billing.mediation.rating.A2ZRater;

import java.math.BigDecimal;

/**
 * The legacy A2ZRater-end leg stamping shared by the A2Z families: stamp the matched leg's prefix, rate,
 * partner cost, billed duration and country code onto the cdr (the CUSTOMER fields for direction Customer,
 * the SUPPLIER fields for direction Supplier).
 */
final class FamilyStamp {
    private FamilyStamp() {}

    static void StampLeg(cdr cdr, Rateext rate, AssignmentDirection direction, A2ZRateResult a2z,
            MediationContext mediation) {
        if (direction == AssignmentDirection.Supplier) {
            cdr.MatchedPrefixSupplier = rate.Prefix;
            cdr.SupplierRate = rate.rateamount;
            cdr.OutPartnerCost = a2z.Amount();
            cdr.Duration2 = a2z.BilledDurationSec();
        } else {
            cdr.MatchedPrefixCustomer = rate.Prefix;
            cdr.CustomerRate = rate.rateamount;
            cdr.InPartnerCost = a2z.Amount();
            cdr.Duration1 = CustomerBilledDuration(cdr, rate, a2z, mediation);
            StampRoundedDuration(cdr, rate);
        }
        cdr.CountryCode = rate.CountryCode;
    }

    /**
     * {@code cdr.Duration1} — the customer leg's billed duration.
     *
     * <p>Legacy writes {@code a2z.BilledDurationSec()}, the amount path's working duration, which A2ZRater
     * leaves at <b>0</b> for every call that runs past {@code SurchargeTime} even though the call IS charged
     * (initial period + rounded remainder). On a plan with an initial period that is most answered calls, so the
     * column reports 0 seconds against a non-zero charge.
     *
     * <p>On a RESELLER tier it is therefore stamped with {@link A2ZRater#GetRatedDurationSec} — the same three
     * surcharge branches the amount path prices, summed instead of dropped, over the same {@link Rateext} the
     * amount was rated with (resolved through THAT tier's own tuple → rateassign → plan → longest-prefix row, so
     * tier 1..N each use their own configuration). Nothing is assumed about the plan: no constant, no
     * reseller-specific value, no dependency on idService=20 or any cost leg.
     *
     * <p>The ROOT/admin tenant keeps the legacy value verbatim ({@code IsResellerTier} false), so its cdrs stay
     * per-call comparable with the legacy biller. {@code acc_chargeable.Quantity} keeps taking
     * {@code a2z.BilledDurationSec()} straight from the family on EVERY tier — this is a cdr reporting column
     * only, and the package-minute deduction (which divides Quantity) is untouched.
     *
     * <p>A null {@code DurationSec} cannot reach here through a family ({@code A2ZRater.Rate} dereferences it
     * first), but the direct-call path is guarded anyway and falls back to the legacy value.
     */
    private static BigDecimal CustomerBilledDuration(cdr cdr, Rateext rate, A2ZRateResult a2z,
            MediationContext mediation) {
        if (mediation == null || !mediation.IsResellerTier || cdr.DurationSec == null) {
            return a2z.BilledDurationSec();
        }
        return A2ZRater.GetRatedDurationSec(cdr.DurationSec, rate);
    }

    /**
     * RoundedDuration = the RATE-PLAN rounding of the ACTUAL duration: actual -&gt; the matched rate's
     * {@code MinDurationSec} threshold -&gt; its {@code Resolution} (pulse) multiple, i.e.
     * {@link A2ZRater#GetA2ZDuration}. {@code rate} here is the {@code Rateext} resolved from THIS tier's
     * assigned rate plan (partner-keyed tuple -&gt; rateassign -&gt; plan -&gt; longest-prefix row), so a
     * multi-tier call gets each tier's own rounding rule.
     *
     * <p><b>Deliberately NOT {@code a2z.BilledDurationSec()}.</b> That value is the AMOUNT path's working
     * duration and is legacy-quirked to 0 whenever the call runs past {@code SurchargeTime} (A2ZRater's
     * else-branch keeps finalDuration 0 while charging the surcharge window + the remainder separately).
     * Reusing it would report 0 for every call longer than the initial period. This is a pure re-derivation
     * from the same rate config — it reads nothing from and writes nothing to the amount path, so billing
     * amounts, Quantity, surcharge behaviour and BilledDurationSec are all untouched.
     *
     * <p>Customer direction only, and it is why reseller/customer-only tenants get a value at all: on SG10 the
     * column was previously a by-product of the idService=20 vendor-cost leg ({@code SfDomOffNetOutIcx}), which
     * a customer-revenue-only tenant has no config for. Where that cost leg DOES run it still assigns
     * {@code RoundedDuration} last ({@code BasicCharge.Rate} runs the rating rules before the ICX block), so
     * tenants that have one keep exactly the value they had before.
     *
     * <p>A null {@code DurationSec} is left alone (the column stays NULL); a matched rate on a 0-duration call
     * yields 0, which is {@link A2ZRater#GetA2ZDuration}'s own zero case.
     *
     * <p>Independent of {@code Duration1}: this is {@code GetA2ZDuration(actual)} — the plan's rounding of the
     * WHOLE call, with no surcharge term — while {@code Duration1} is the rated duration, which splits the call
     * at {@code SurchargeTime}. On a plan with an initial period the two legitimately differ, and neither reads
     * the other. It is also stamped on every tier, reseller or not.
     */
    private static void StampRoundedDuration(cdr cdr, Rateext rate) {
        if (cdr.DurationSec == null) return;
        cdr.RoundedDuration = A2ZRater.GetA2ZDuration(cdr.DurationSec, rate);
    }
}
