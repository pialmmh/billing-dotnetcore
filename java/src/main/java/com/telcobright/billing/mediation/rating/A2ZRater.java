package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.enumbillingspan;
import com.telcobright.billing.mediation.engine.models.rateplan;
import com.telcobright.billing.mediation.rating.ratecaching.FractionCeilingHelper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Faithful port of legacy {@code A2ZRater.ExecuteA2ZRating} (the duration + amount branching) together with
 * {@code PrefixMatcher.GetA2ZDuration} + {@code GetA2ZAmountWithOutSurCharge} + {@code GetA2ZAmountWithSurCharge}
 * + {@code GetBillingSpanByRateOrIfMissingByRatePlan}, over the matched {@link Rateext}.
 *
 * <p>The three legacy branches are replicated EXACTLY, including the quirk that {@code finalDuration} stays 0
 * when the duration exceeds the surcharge (initial-period) time:
 * <ul>
 * <li>{@code SurchargeTime == 0}: finalDuration = GetA2ZDuration(dur); amount = GetA2ZAmountWithOutSurCharge.</li>
 * <li>{@code SurchargeTime > 0 && dur <= SurchargeTime}: finalDuration = SurchargeTime; amount =
 *   GetA2ZAmountWithSurCharge(SurchargeTime).</li>
 * <li>{@code else}: amount = GetA2ZAmountWithSurCharge(SurchargeTime) + GetA2ZAmountWithSurCharge(
 *   GetA2ZDuration(dur - SurchargeTime)); <b>finalDuration STAYS 0</b> (legacy quirk — not "fixed").</li>
 * </ul>
 * Then, if {@code 0 < OtherAmount9 <= 7}, the amount is fraction-ceilinged at that position. Billing span is
 * {@code rate.billingspan} when &gt; 0, else {@code BillingSpans[rateplan.BillingSpan].value} (throws if &le; 0).
 * The rate amount is pre-rounded by {@code rate.RateAmountRoundupDecimal} (else the rate plan's). The final
 * amount is rounded to {@code MaxDecimalPrecision} HALF_EVEN.</p>
 *
 * <p>The two amount methods differ EXACTLY as in legacy: {@code WithOutSurCharge} adds the FLAT
 * {@code rate.SurchargeAmount}; {@code WithSurCharge} bills the surcharge seconds at the rate. {@code ExecuteA2ZRating}
 * uses {@code WithOutSurCharge} only when {@code SurchargeTime == 0} (so its flat block is never hit on this path),
 * and {@code WithSurCharge} for the surcharge branches — both ported verbatim for fidelity.</p>
 */
public final class A2ZRater {

    private A2ZRater() {}

    /** The A2Z rating result: the (possibly quirk-zero) billed duration + the amount. */
    public static A2ZRateResult Rate(Rateext rate, BigDecimal actualDurationSec,
            Map<String, rateplan> dicRatePlan, Map<String, enumbillingspan> billingSpans, int maxDecimalPrecision) {
        BigDecimal finalDuration = BigDecimal.ZERO;
        BigDecimal finalAmount;

        if (rate.SurchargeTime == 0) {
            finalDuration = GetA2ZDuration(actualDurationSec, rate);
            finalAmount = GetA2ZAmountWithOutSurCharge(finalDuration, rate, 0, dicRatePlan, billingSpans, maxDecimalPrecision);
        } else { // pulse = 30/6, duration = 12.071, initialperiodcharge = 30, resolution = 6
            if (actualDurationSec.compareTo(BigDecimal.valueOf(rate.SurchargeTime)) <= 0) {
                finalDuration = BigDecimal.valueOf(rate.SurchargeTime);
                finalAmount = GetA2ZAmountWithSurCharge(finalDuration, rate, 0, dicRatePlan, billingSpans, maxDecimalPrecision);
            } else { // dur > SurchargeTime
                BigDecimal surchargeDuration = BigDecimal.valueOf(rate.SurchargeTime);
                BigDecimal surchhargeAmount = GetA2ZAmountWithSurCharge(surchargeDuration, rate, 0, dicRatePlan, billingSpans, maxDecimalPrecision);

                BigDecimal durationAfterInitialPeriod = actualDurationSec.subtract(surchargeDuration);
                BigDecimal roundedDurationAfterInitialPeriod = GetA2ZDuration(durationAfterInitialPeriod, rate);

                BigDecimal amountAfterCharge = GetA2ZAmountWithSurCharge(roundedDurationAfterInitialPeriod, rate, 0, dicRatePlan, billingSpans, maxDecimalPrecision);
                finalAmount = surchhargeAmount.add(amountAfterCharge);
                // finalDuration STAYS 0 in this branch — legacy quirk, preserved verbatim.
            }
        }

        int ceilingUpPositionAfterDecimal = rate.OtherAmount9 != null ? Math.round(rate.OtherAmount9) : 0;
        if (ceilingUpPositionAfterDecimal > 0 && ceilingUpPositionAfterDecimal <= 7) {
            FractionCeilingHelper ceilingHelper = new FractionCeilingHelper(finalAmount, ceilingUpPositionAfterDecimal);
            finalAmount = ceilingHelper.GetPreciseDecimal();
        }
        return new A2ZRateResult(finalDuration, finalAmount);
    }

    /**
     * Pulse-adjusted duration (legacy PrefixMatcher.GetA2ZDuration). MinDurationSec is a millisecond-rounding
     * threshold (&lt;0 = use actual, &gt;0 = ceil if frac &gt;= threshold else floor, =0 = always ceil), then ceil up
     * to the Resolution (pulse) multiple.
     */
    public static BigDecimal GetA2ZDuration(BigDecimal actualDurationSec, Rateext thisRate) {
        if (actualDurationSec.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        BigDecimal minDurationSec = new BigDecimal(Float.toString(thisRate.MinDurationSec));

        if (minDurationSec.compareTo(BigDecimal.ZERO) < 0) { // no rounding, use actual duration
            return actualDurationSec;
        } else if (minDurationSec.compareTo(BigDecimal.ZERO) > 0) { // e.g. minimum .1 sec required to round up
            BigDecimal floorDuration = actualDurationSec.setScale(0, RoundingMode.FLOOR);
            BigDecimal miliSecPart = actualDurationSec.subtract(floorDuration);
            if (miliSecPart.compareTo(minDurationSec) >= 0) {
                actualDurationSec = actualDurationSec.setScale(0, RoundingMode.CEILING);
            } else {
                actualDurationSec = actualDurationSec.setScale(0, RoundingMode.FLOOR);
            }
        } else { // always round up
            actualDurationSec = actualDurationSec.setScale(0, RoundingMode.CEILING);
        }

        long lngDuration = actualDurationSec.longValue();
        if (thisRate.Resolution > 0) {
            long lngResolution = thisRate.Resolution;
            if (lngDuration % lngResolution > 0) {
                lngDuration = ((lngDuration / lngResolution) + 1) * lngResolution;
            } else {
                lngDuration = (lngDuration / lngResolution) * lngResolution;
            }
        }
        return BigDecimal.valueOf(lngDuration);
    }

    // legacy GetA2ZAmountWithOutSurCharge — surcharge (if any) added as the FLAT rate.SurchargeAmount.
    public static BigDecimal GetA2ZAmountWithOutSurCharge(BigDecimal finalDurationSec, Rateext thisRate,
            int rateFieldNumber, Map<String, rateplan> dicRatePlan, Map<String, enumbillingspan> billingSpans,
            int maxDecimalPrecision) {
        if (finalDurationSec.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal thisRateAmount = SelectRateAmount(thisRate, rateFieldNumber, maxDecimalPrecision);

        BigDecimal surchargeDuration = BigDecimal.ZERO;
        BigDecimal surchareAmount = BigDecimal.ZERO;
        BigDecimal durationExcludingSurcharge = finalDurationSec;

        if (thisRate.SurchargeTime > 0) { // surcharge applicable
            if (finalDurationSec.compareTo(BigDecimal.valueOf(thisRate.SurchargeTime)) >= 0) {
                surchargeDuration = BigDecimal.valueOf(thisRate.SurchargeTime);
            } else {
                surchargeDuration = finalDurationSec;
            }
            durationExcludingSurcharge = finalDurationSec.subtract(surchargeDuration);
            surchareAmount = thisRate.SurchargeAmount != null ? thisRate.SurchargeAmount : BigDecimal.ZERO; // FLAT
        }

        long bspanSec = GetBillingSpanByRateOrIfMissingByRatePlan(thisRate, dicRatePlan, billingSpans);
        thisRateAmount = ApplyRateAmountRoundup(thisRateAmount, thisRate, dicRatePlan);

        BigDecimal billedAmountExcludingSurcharge = durationExcludingSurcharge
                .multiply(thisRateAmount.divide(BigDecimal.valueOf(bspanSec), MathContext.DECIMAL128));
        BigDecimal finalAmount = billedAmountExcludingSurcharge.add(surchareAmount);
        if (maxDecimalPrecision > 0) finalAmount = finalAmount.setScale(maxDecimalPrecision, RoundingMode.HALF_EVEN);
        return finalAmount;
    }

    // legacy GetA2ZAmountWithSurCharge — surcharge seconds billed AT THE RATE (not flat).
    public static BigDecimal GetA2ZAmountWithSurCharge(BigDecimal finalDurationSec, Rateext thisRate,
            int rateFieldNumber, Map<String, rateplan> dicRatePlan, Map<String, enumbillingspan> billingSpans,
            int maxDecimalPrecision) {
        if (finalDurationSec.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal thisRateAmount = SelectRateAmount(thisRate, rateFieldNumber, maxDecimalPrecision);

        BigDecimal durationToBeChargeble = BigDecimal.ZERO;
        BigDecimal surchareAmount = BigDecimal.ZERO;
        BigDecimal durationExcludingSurcharge = finalDurationSec;
        long bspanSec = GetBillingSpanByRateOrIfMissingByRatePlan(thisRate, dicRatePlan, billingSpans);

        if (thisRate.SurchargeTime > 0) { // surcharge applicable
            if (finalDurationSec.compareTo(BigDecimal.valueOf(thisRate.SurchargeTime)) >= 0) {
                durationToBeChargeble = BigDecimal.valueOf(thisRate.SurchargeTime);
            } else {
                durationToBeChargeble = finalDurationSec;
            }
            durationExcludingSurcharge = finalDurationSec.subtract(durationToBeChargeble);
            surchareAmount = durationToBeChargeble.multiply(thisRateAmount.divide(BigDecimal.valueOf(bspanSec), MathContext.DECIMAL128));
        }

        thisRateAmount = ApplyRateAmountRoundup(thisRateAmount, thisRate, dicRatePlan);

        BigDecimal billedAmountExcludingSurcharge = durationExcludingSurcharge
                .multiply(thisRateAmount.divide(BigDecimal.valueOf(bspanSec), MathContext.DECIMAL128));
        BigDecimal finalAmount = billedAmountExcludingSurcharge.add(surchareAmount);
        if (maxDecimalPrecision > 0) finalAmount = finalAmount.setScale(maxDecimalPrecision, RoundingMode.HALF_EVEN);
        return finalAmount;
    }

    // legacy GetBillingSpanByRateOrIfMissingByRatePlan.
    static long GetBillingSpanByRateOrIfMissingByRatePlan(Rateext rate, Map<String, rateplan> dicRatePlan,
            Map<String, enumbillingspan> billingSpans) {
        long bspanSec = rate.billingspan != null ? rate.billingspan : 0L; // Convert.ToInt64(null) == 0
        if (bspanSec > 0) return bspanSec;
        rateplan rp = dicRatePlan.get(rate.idrateplan != null ? rate.idrateplan.toString() : "");
        if (rp == null) throw new RuntimeException("Rate plan not found for billing span lookup: idrateplan=" + rate.idrateplan);
        String strTimeFreqUom = rp.BillingSpan;
        enumbillingspan span = billingSpans.get(strTimeFreqUom);
        if (span == null) throw new RuntimeException("Billing span uom not found: " + strTimeFreqUom);
        bspanSec = span.value;
        if (bspanSec <= 0) throw new RuntimeException("Billing Span Value Must be > 0");
        return bspanSec;
    }

    // legacy: rate.RateAmountRoundupDecimal (if > 0) else the rate plan's; Math.Round(amount, n) when n > 0.
    private static BigDecimal ApplyRateAmountRoundup(BigDecimal thisRateAmount, Rateext rate,
            Map<String, rateplan> dicRatePlan) {
        int rateAmountRoundUpTo;
        if (rate.RateAmountRoundupDecimal != null && rate.RateAmountRoundupDecimal > 0) {
            rateAmountRoundUpTo = rate.RateAmountRoundupDecimal;
        } else {
            rateplan rp = dicRatePlan.get(rate.idrateplan != null ? rate.idrateplan.toString() : "");
            Integer planRoundup = rp != null ? rp.RateAmountRoundupDecimal : null;
            rateAmountRoundUpTo = planRoundup != null ? planRoundup : 0; // Convert.ToInt32(null) == 0
        }
        if (rateAmountRoundUpTo > 0) {
            thisRateAmount = thisRateAmount.setScale(rateAmountRoundUpTo, RoundingMode.HALF_EVEN); // Math.Round (banker's)
        }
        return thisRateAmount;
    }

    // legacy rateFieldNumber switch: 0 = rateamount; 1..9 = Convert.ToDecimal(OtherAmountN).RoundFractionsUpTo
    // (= decimal.Round HALF_EVEN to maxDecimalPrecision). ExecuteA2ZRating always passes 0.
    private static BigDecimal SelectRateAmount(Rateext r, int rateFieldNumber, int maxDecimalPrecision) {
        BigDecimal v;
        switch (rateFieldNumber) {
            case 0: return r.rateamount;
            case 1: v = r.OtherAmount1; break;
            case 2: v = r.OtherAmount2; break;
            case 3: v = r.OtherAmount3; break;
            case 4: v = r.OtherAmount4; break;
            case 5: v = r.OtherAmount5; break;
            case 6: v = r.OtherAmount6; break;
            case 7: v = r.OtherAmount7 != null ? new BigDecimal(Float.toString(r.OtherAmount7)) : null; break;
            case 8: v = r.OtherAmount8 != null ? new BigDecimal(Float.toString(r.OtherAmount8)) : null; break;
            case 9: v = r.OtherAmount9 != null ? new BigDecimal(Float.toString(r.OtherAmount9)) : null; break;
            default: return BigDecimal.ZERO;
        }
        BigDecimal amount = v != null ? v : BigDecimal.ZERO; // Convert.ToDecimal(null) == 0
        return maxDecimalPrecision > 0 ? amount.setScale(maxDecimalPrecision, RoundingMode.HALF_EVEN) : amount;
    }
}
