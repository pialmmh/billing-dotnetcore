package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRateResult;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.sms.SmsCompositePrefix;
import com.telcobright.billing.mediation.sms.SmsOutgoing;

import java.math.BigDecimal;

/**
 * SF 10 for OUTGOING SMS (SG20) — the legacy {@code SfA2ZWithVatTax} customer leg as the legacy SMS biller ran it,
 * registered ONLY in the SMS rater (voice keeps {@link SfA2ZWithVatTax}).
 *
 * <ul>
 * <li><b>Amount</b>: the unchanged legacy A2Z amount over {@code DurationSec} — the ONLY billing duration (the
 *   record's {@code durationSec}; nothing else, e.g. {@code smsCount}, is ever consulted). Billing units are
 *   {@code DurationSec / 60} on a per-minute ({@code TF_min}) plan, so the amount is {@code units × rate} on both
 *   the plain and the surcharge-window branch.</li>
 * <li><b>Quantity / Duration1</b>: the rated seconds ({@link A2ZRater#GetRatedDurationSec}) — 60/120/180 for
 *   1/2/3 units. Legacy left these at 0 above 60 s on a surcharge plan (an amount-path quirk); nothing
 *   downstream consumes the zero (the legacy SMS report divides {@code duration1} by 60), so it is NOT kept.</li>
 * <li><b>RoundedDuration</b>: left NULL — the legacy SMS biller never stamped it, and SMS has no pulse rounding.</li>
 * <li><b>Prefix</b>: the matched raw composite rendered {@code BRAND|8801} on {@code MatchedPrefixCustomer} and
 *   {@code acc_chargeable.Prefix} (matching itself used the raw 0x1F form).</li>
 * <li><b>Tax</b>: {@code amount × OtherAmount3} (legacy {@code InPartnerCost × OtherAmount3}; 0 on the live SMS plans).</li>
 * </ul>
 * Legacy returned NO chargeable for a non-charged SMS ({@code ChargingStatus == 0}).
 */
public final class SfSmsA2ZWithVatTax implements IServiceFamily {
    @Override public int Id() { return SmsOutgoing.CustomerServiceFamilyId; }

    @Override
    public acc_chargeable Charge(Rateext rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            MediationContext mediation) {
        if (direction != AssignmentDirection.Customer)
            throw new IllegalArgumentException("SMS SF10 rates the customer leg only, not " + direction);
        if (cdr.ChargingStatus == null || cdr.ChargingStatus != 1) return null;

        String display = SmsCompositePrefix.ToDisplay(rate.RawPrefix != null ? rate.RawPrefix : rate.Prefix);
        if (display != null && display.length() > SmsOutgoing.ChargeablePrefixMaxLen)
            throw new IllegalStateException("SMS matched prefix '" + display + "' exceeds acc_chargeable.Prefix width "
                    + SmsOutgoing.ChargeablePrefixMaxLen);

        int maxDecimalPrecision = mediation.MaxDecimalPrecision;
        A2ZRateResult a2z = A2ZRater.Rate(rate, cdr.DurationSec, mediation.DicRatePlan, mediation.BillingSpans,
                maxDecimalPrecision);
        BigDecimal ratedSeconds = A2ZRater.GetRatedDurationSec(cdr.DurationSec, rate);

        cdr.MatchedPrefixCustomer = display;
        cdr.CustomerRate = rate.rateamount;
        cdr.InPartnerCost = a2z.Amount();
        cdr.Duration1 = ratedSeconds;
        cdr.CountryCode = rate.CountryCode;
        cdr.PDD = 0f;           // legacy rated SMS rows: PDD 0, NERSuccess 0
        cdr.NERSuccess = 0;

        var otherAmount3 = rate.OtherAmount3 != null ? rate.OtherAmount3 : BigDecimal.ZERO;
        var tax = ChargeableBuilder.Round(a2z.Amount().multiply(otherAmount3), maxDecimalPrecision);
        cdr.Tax1 = tax;

        acc_chargeable c = ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), direction,
                a2z.Amount(), ratedSeconds, tax, mediation);
        c.Prefix = display;
        return c;
    }
}
