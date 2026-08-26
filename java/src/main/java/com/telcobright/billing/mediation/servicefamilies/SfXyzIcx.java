package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRater;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SF 7 — the customer family for SG 15 (International Outgoing IPTSP), legacy {@code SfXyzIcx} rated by
 * {@code XyzRuleHelper} with {@code XyzRatingType.Icx}. It is the "x/y/z" international settlement model, NOT a
 * flat per-minute charge:
 * <ul>
 *   <li><b>x</b> ({@code XAmount}, BDT) = customer gross = {@code GetA2ZAmountWithOutSurCharge(dur, rate, field 0 =
 *       rateamount)} — the customer (x) rate.</li>
 *   <li><b>y</b> ({@code YAmount}, USD) = supplier cost = {@code GetA2ZAmountWithOutSurCharge(dur, rate, field 1 =
 *       OtherAmount1)} — the supplier (y) rate, denominated in USD.</li>
 *   <li><b>yBdt</b> = y × {@code UsdRateY} (the monthly USD→BDT factor).</li>
 *   <li><b>z</b> ({@code ZAmount}, BDT) = x − yBdt — the margin.</li>
 *   <li><b>invoice</b> ({@code BilledAmount} / {@code RevenueIcxOut}) = z × {@code OtherAmount2}/100 (the operator's
 *       margin-share %, e.g. 15) — the <b>Icx</b> branch (Igw would add yBdt back; SG15 does NOT).</li>
 *   <li><b>Tax2</b> = invoice × {@code OtherAmount3}/100 (the BTRC revenue-share %, e.g. 50).</li>
 * </ul>
 *
 * <p>Resolved SERVICE-WIDE (idService=7, one common plan — production "Outgoing XYZ @IGW New Rate", plan 117),
 * matched on the international {@code OriginatingCalledNumber} (the {@code 00…} destination, {@code 00} stripped).
 * The customer is the InPartner. Rated ONLY when {@code ChargingStatus == 1} — a failed/0-duration call is still
 * classified SG15 but produces no chargeable and no financial amounts (legacy parity).
 *
 * <p><b>Fail-loud:</b> if the USD→BDT rate for the call's month is absent, this throws
 * {@link MissingUsdRateException} rather than silently billing z (and therefore the invoice) as if USD were free —
 * an SG15 call must never be zero-rated because a config lookup failed.
 */
public final class SfXyzIcx implements IServiceFamily {
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Thrown when an answered SG15 call has no USD→BDT conversion for its month — never bill zero silently. */
    public static final class MissingUsdRateException extends RuntimeException {
        public MissingUsdRateException(String m) { super(m); }
    }

    @Override public int Id() { return 7; }

    @Override
    public acc_chargeable Charge(Rateext rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            MediationContext mediation) {
        int maxDec = mediation.MaxDecimalPrecision;

        // finalDuration = pulse-rounded billable seconds (legacy XyzRuleHelper uses GetA2ZDuration).
        BigDecimal finalDuration = A2ZRater.GetA2ZDuration(
                cdr.DurationSec != null ? cdr.DurationSec : BigDecimal.ZERO, rate);

        // x = customer gross (BDT) over the x-rate (rateamount); y = supplier cost (USD) over the y-rate (OtherAmount1).
        BigDecimal xAmountBdt = A2ZRater.GetA2ZAmountWithOutSurCharge(
                finalDuration, rate, 0, mediation.DicRatePlan, mediation.BillingSpans, maxDec);
        BigDecimal yAmountUsd = A2ZRater.GetA2ZAmountWithOutSurCharge(
                finalDuration, rate, 1, mediation.DicRatePlan, mediation.BillingSpans, maxDec);

        // USD -> BDT for the CALL's month (legacy: uom_conversion_dated, exact-month else nearest-earlier).
        BigDecimal usdRateY = mediation.UsdToBdtForMonth(
                cdr.AnswerTime != null ? cdr.AnswerTime : cdr.StartTime);
        if (usdRateY == null) {
            throw new MissingUsdRateException("SG15/Xyz: no USD->BDT conversion for month of "
                    + (cdr.AnswerTime != null ? cdr.AnswerTime : cdr.StartTime)
                    + " (uniqueBillId=" + cdr.UniqueBillId + ", dest=" + cdr.OriginatingCalledNumber + ")");
        }

        BigDecimal yBdt = round(yAmountUsd.multiply(usdRateY), maxDec);
        BigDecimal zAmount = round(xAmountBdt.subtract(yBdt), maxDec);
        BigDecimal marginSharePct = rate.OtherAmount2 != null ? rate.OtherAmount2 : BigDecimal.ZERO;    // e.g. 15
        BigDecimal fifteenPcOfZ = round(zAmount.multiply(marginSharePct).divide(HUNDRED, java.math.MathContext.DECIMAL128), maxDec);
        BigDecimal finalAmount = fifteenPcOfZ;                                                          // Icx branch
        BigDecimal btrcPct = rate.OtherAmount3 != null ? rate.OtherAmount3 : BigDecimal.ZERO;           // e.g. 50
        BigDecimal btrcAmount = round(fifteenPcOfZ.multiply(btrcPct).divide(HUNDRED, java.math.MathContext.DECIMAL128), maxDec);

        // stamp the cdr (legacy XyzRuleHelper): x/y/z, USD rate, RevenueIcxOut, Tax2, durations, matched prefix.
        cdr.XAmount = round(xAmountBdt, maxDec);
        cdr.YAmount = round(yAmountUsd, maxDec);
        cdr.ZAmount = zAmount;
        cdr.UsdRateY = usdRateY;
        cdr.RevenueIcxOut = finalAmount;
        cdr.Tax2 = btrcAmount;
        cdr.RoundedDuration = finalDuration;
        cdr.Duration3 = HundredMsDuration(cdr.DurationSec);
        cdr.MatchedPrefixY = rate.Prefix;
        cdr.MatchedPrefixCustomer = rate.Prefix;
        if (cdr.CountryCode == null || cdr.CountryCode.isEmpty()) cdr.CountryCode = rate.CountryCode;
        cdr.CustomerRate = rate.rateamount;

        // customer chargeable (assignedDirection=1). BilledAmount = invoice (fifteenPcOfZ); x/y/z on OtherAmount1/2/3;
        // the x-rate / y-rate(USD) / usd-rate on OtherDecAmount1/2/3 (legacy field mapping, read by the SG15 summary).
        acc_chargeable c = ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), AssignmentDirection.Customer,
                finalAmount, finalDuration, btrcAmount, mediation);
        c.OtherAmount1 = cdr.XAmount;
        c.OtherAmount2 = cdr.YAmount;
        c.OtherAmount3 = zAmount;
        c.OtherDecAmount1 = rate.rateamount;                                   // x rate
        c.OtherDecAmount2 = rate.OtherAmount1;                                 // y rate (USD)
        c.OtherDecAmount3 = usdRateY;                                          // usd rate
        return c;
    }

    private static BigDecimal round(BigDecimal v, int maxDec) {
        return maxDec > 0 ? v.setScale(maxDec, RoundingMode.HALF_EVEN) : v;
    }

    /** Legacy PrefixMatcher.HundredMsDuration — ceil if fractional part >= 0.1s, else floor; 0 stays 0. */
    private static BigDecimal HundredMsDuration(BigDecimal actualDurationSec) {
        if (actualDurationSec == null || actualDurationSec.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal floor = actualDurationSec.setScale(0, RoundingMode.FLOOR);
        return actualDurationSec.subtract(floor).compareTo(new BigDecimal("0.1")) >= 0
                ? actualDurationSec.setScale(0, RoundingMode.CEILING) : floor;
    }
}
