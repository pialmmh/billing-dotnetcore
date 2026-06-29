package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRater;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * SF 11 — the customer family for SG 11 (legacy {@code SfDomOffNetInAns}, the domestic-incoming ANS
 * charge). NOT the basic A2Z amount — it recomputes a termination (ANS) charge:
 * <ul>
 * <li>billed duration = pulse-rounded actual duration;</li>
 * <li>effective rate = {@code rateamount − OtherAmount1} (OtherAmount1 = the IOF/additional charge);</li>
 * <li>ANS amount = {@code billedDuration × effectiveRate / 60};</li>
 * <li>BTRC tax = {@code ANS amount × OtherAmount3} (OtherAmount3 = the BTRC fraction).</li>
 * </ul>
 * The chargeable's BilledAmount is the ANS amount; TaxAmount1 is the BTRC. Mutates the cdr's
 * Tax1/RevenueIgwOut/CountryCode/MatchedPrefixSupplier/RoundedDuration/CustomerRate as the legacy did.
 * (Legacy used a ceiling-style RoundFractionsUpTo; this uses decimal.Round — fidelity note.)
 */
public final class SfDomOffNetInAns implements IServiceFamily {
    @Override public int Id() { return 11; }

    @Override
    public acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            int maxDecimalPrecision) {
        var billedDuration = A2ZRater.A2ZDuration(cdr.DurationSec, rate);

        var iof = ChargeableBuilder.Round(
                new BigDecimal(Float.toString(rate.OtherAmount1 != null ? rate.OtherAmount1 : 0f)), maxDecimalPrecision);
        var effectiveRate = rate.rateamount.subtract(iof);
        var ansAmount = ChargeableBuilder.Round(
                billedDuration.multiply(effectiveRate).divide(BigDecimal.valueOf(60), MathContext.DECIMAL128),
                maxDecimalPrecision);

        var btrcFraction = ChargeableBuilder.Round(
                new BigDecimal(Float.toString(rate.OtherAmount3 != null ? rate.OtherAmount3 : 0f)), maxDecimalPrecision);
        var btrcAmount = ChargeableBuilder.Round(ansAmount.multiply(btrcFraction), maxDecimalPrecision);

        cdr.Tax1 = btrcAmount;
        cdr.RevenueIgwOut = ansAmount;
        cdr.CountryCode = rate.CountryCode;
        cdr.MatchedPrefixSupplier = Integer.toString(rate.Prefix);
        cdr.RoundedDuration = billedDuration;
        cdr.CustomerRate = rate.rateamount;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), direction,
                ansAmount, billedDuration, btrcAmount);
    }
}
