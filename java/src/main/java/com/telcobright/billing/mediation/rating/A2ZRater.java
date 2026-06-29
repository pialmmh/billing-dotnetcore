package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.engine.models.rateassign;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Faithful port of legacy {@code A2ZRater.ExecuteA2ZRating} (the duration + amount branching) over a
 * {@code rateassign}. Two surcharge modes:
 * <ul>
 * <li>{@code SurchargeTime == 0}: bill the pulse-rounded whole duration.</li>
 * <li>{@code SurchargeTime > 0}: the first {@code SurchargeTime} seconds are a MINIMUM initial period
 *   (billed in full, not pulse-rounded); the remainder is pulse-rounded and added.</li>
 * </ul>
 * In every case the amount is {@code billedDuration x (rateamount / billingSpan)} — the legacy
 * {@code GetA2ZAmountWithSurCharge}/{@code WithOutSurCharge} reduce to this (the surcharge split is at the
 * same rate). The flat {@code SurchargeAmount} field is NOT used by this model. {@code billingSpanSec} is
 * 60 (per-minute) until the rate plan's BillingSpan is wired; {@code RateAmountRoundupDecimal} (rate-plan
 * level) is deferred — pass-throughs noted.
 */
public final class A2ZRater {

    private A2ZRater() {}

    public static A2ZRateResult Rate(rateassign rate, BigDecimal actualDurationSec) {
        return Rate(rate, actualDurationSec, 60, 8);
    }

    public static A2ZRateResult Rate(rateassign rate, BigDecimal actualDurationSec, int billingSpanSec) {
        return Rate(rate, actualDurationSec, billingSpanSec, 8);
    }

    public static A2ZRateResult Rate(rateassign rate, BigDecimal actualDurationSec,
            int billingSpanSec, int maxDecimalPrecision) {
        var billedDuration = BilledDuration(rate, actualDurationSec);
        var amount = Amount(rate, billedDuration, billingSpanSec, maxDecimalPrecision);
        return new A2ZRateResult(billedDuration, amount);
    }

    /** The billed duration under the surcharge/pulse model (legacy ExecuteA2ZRating). */
    public static BigDecimal BilledDuration(rateassign rate, BigDecimal actualDurationSec) {
        if (actualDurationSec.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        if (rate.SurchargeTime == 0)
            return A2ZDuration(actualDurationSec, rate);                      // whole duration, pulse-rounded

        if (actualDurationSec.compareTo(BigDecimal.valueOf(rate.SurchargeTime)) <= 0)
            return BigDecimal.valueOf(rate.SurchargeTime);                    // minimum initial period

        // initial period (full) + pulse-rounded remainder. (Legacy left finalDuration 0 in this branch —
        // a latent bug; we record the actual billed total.)
        return BigDecimal.valueOf(rate.SurchargeTime)
                .add(A2ZDuration(actualDurationSec.subtract(BigDecimal.valueOf(rate.SurchargeTime)), rate));
    }

    /**
     * Pulse-adjusted duration (legacy PrefixMatcher.GetA2ZDuration). MinDurationSec is a
     * millisecond-rounding threshold (&lt;0 = actual, &gt;0 = ceil if frac &gt;= threshold else floor, =0 =
     * always ceil), then ceil up to the Resolution (pulse) multiple.
     */
    public static BigDecimal A2ZDuration(BigDecimal actualDurationSec, rateassign rate) {
        if (actualDurationSec.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        var minDurationSec = new BigDecimal(Float.toString(rate.MinDurationSec));
        if (minDurationSec.compareTo(BigDecimal.ZERO) < 0) return actualDurationSec;

        if (minDurationSec.compareTo(BigDecimal.ZERO) > 0) {
            var frac = actualDurationSec.subtract(actualDurationSec.setScale(0, RoundingMode.FLOOR));
            actualDurationSec = frac.compareTo(minDurationSec) >= 0 ? actualDurationSec.setScale(0, RoundingMode.CEILING)
                                                                    : actualDurationSec.setScale(0, RoundingMode.FLOOR);
        } else {
            actualDurationSec = actualDurationSec.setScale(0, RoundingMode.CEILING);
        }

        long lng = actualDurationSec.longValue();
        if (rate.Resolution > 0) {
            long res = rate.Resolution;
            lng = lng % res > 0 ? ((lng / res) + 1) * res : (lng / res) * res;
        }
        return BigDecimal.valueOf(lng);
    }

    private static BigDecimal Amount(rateassign rate, BigDecimal billedDurationSec, int billingSpanSec, int maxDecimalPrecision) {
        if (billedDurationSec.compareTo(BigDecimal.ZERO) == 0 || billingSpanSec <= 0) return BigDecimal.ZERO;
        var amount = billedDurationSec.multiply(rate.rateamount.divide(BigDecimal.valueOf(billingSpanSec), MathContext.DECIMAL128));
        return maxDecimalPrecision > 0 ? amount.setScale(maxDecimalPrecision, RoundingMode.HALF_EVEN) : amount;
    }
}
