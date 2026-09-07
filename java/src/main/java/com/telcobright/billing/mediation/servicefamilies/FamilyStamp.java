package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRateResult;
import com.telcobright.billing.mediation.rating.A2ZRater;

/**
 * The legacy A2ZRater-end leg stamping shared by the A2Z families: stamp the matched leg's prefix, rate,
 * partner cost, billed duration and country code onto the cdr (the CUSTOMER fields for direction Customer,
 * the SUPPLIER fields for direction Supplier).
 */
final class FamilyStamp {
    private FamilyStamp() {}

    static void StampLeg(cdr cdr, Rateext rate, AssignmentDirection direction, A2ZRateResult a2z) {
        if (direction == AssignmentDirection.Supplier) {
            cdr.MatchedPrefixSupplier = rate.Prefix;
            cdr.SupplierRate = rate.rateamount;
            cdr.OutPartnerCost = a2z.Amount();
            cdr.Duration2 = a2z.BilledDurationSec();
        } else {
            cdr.MatchedPrefixCustomer = rate.Prefix;
            cdr.CustomerRate = rate.rateamount;
            cdr.InPartnerCost = a2z.Amount();
            cdr.Duration1 = a2z.BilledDurationSec();
            StampRoundedDuration(cdr, rate);
        }
        cdr.CountryCode = rate.CountryCode;
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
     */
    private static void StampRoundedDuration(cdr cdr, Rateext rate) {
        if (cdr.DurationSec == null) return;
        cdr.RoundedDuration = A2ZRater.GetA2ZDuration(cdr.DurationSec, rate);
    }
}
