using Billing.Mediation.Model;

namespace Billing.Mediation.Rating;

/// <summary>The basic A2Z charge for one leg, billsec → (billed duration, amount).</summary>
public readonly record struct A2ZChargeResult(decimal BilledDurationSec, decimal Amount);

/// <summary>
/// The basic per-leg A2Z charge — a faithful port of the legacy PrefixMatcher math
/// (GetA2ZDuration + GetA2ZAmountWithOutSurCharge), as a pure function over a today-RateCache
/// <see cref="Rate"/>. No DB, no context — unit-testable. Extended legs (AnsCost/BTRC/VAT) come later.
///
/// Duration: <see cref="Rate.MinDurationSec"/> is a millisecond-rounding threshold
///   (&lt;0 = use actual, &gt;0 = ceil only if the fractional part ≥ threshold else floor, =0 = always ceil),
///   then the whole-second duration is ceiled up to a multiple of <see cref="Rate.Resolution"/> (the pulse).
/// Amount: the first <see cref="Rate.SurchargeTime"/> seconds cost a flat <see cref="Rate.SurchargeAmount"/>;
///   the remaining seconds cost <c>rateAmount / billingSpanSec</c> each.
/// </summary>
public static class A2ZCharger
{
    public static A2ZChargeResult Compute(Rate rate, decimal actualDurationSec, int billingSpanSec,
        int maxDecimalPrecision = 8)
    {
        var billedDuration = A2ZDuration(actualDurationSec, rate);
        var amount = A2ZAmount(billedDuration, rate, billingSpanSec, maxDecimalPrecision);
        return new A2ZChargeResult(billedDuration, amount);
    }

    /// <summary>Pulse-adjusted billed duration (legacy PrefixMatcher.GetA2ZDuration).</summary>
    public static decimal A2ZDuration(decimal actualDurationSec, Rate rate)
    {
        if (actualDurationSec == 0) return 0;

        var minDurationSec = rate.MinDurationSec;
        if (minDurationSec < 0M) return actualDurationSec;   // no rounding — use actual

        if (minDurationSec > 0M)
        {
            var frac = actualDurationSec - decimal.Floor(actualDurationSec);
            actualDurationSec = frac >= minDurationSec ? decimal.Ceiling(actualDurationSec)
                                                       : decimal.Floor(actualDurationSec);
        }
        else // == 0: always round up
        {
            actualDurationSec = decimal.Ceiling(actualDurationSec);
        }

        var lng = (long)actualDurationSec;
        if (rate.Resolution > 0)
        {
            long res = rate.Resolution;
            lng = lng % res > 0 ? ((lng / res) + 1) * res : (lng / res) * res;
        }
        return lng;
    }

    /// <summary>Amount for a billed duration (legacy PrefixMatcher.GetA2ZAmountWithOutSurCharge).</summary>
    public static decimal A2ZAmount(decimal billedDurationSec, Rate rate, int billingSpanSec, int maxDecimalPrecision)
    {
        if (billedDurationSec == 0 || billingSpanSec <= 0) return 0;

        decimal surchargeDuration = 0, surchargeAmount = 0;
        var durationExcludingSurcharge = billedDurationSec;
        if (rate.SurchargeTime > 0)
        {
            surchargeDuration = billedDurationSec >= rate.SurchargeTime ? rate.SurchargeTime : billedDurationSec;
            durationExcludingSurcharge = billedDurationSec - surchargeDuration;
            surchargeAmount = rate.SurchargeAmount;
        }

        var billed = durationExcludingSurcharge * (rate.RateAmount / billingSpanSec);
        var finalAmount = billed + surchargeAmount;
        if (maxDecimalPrecision > 0) finalAmount = decimal.Round(finalAmount, maxDecimalPrecision);
        return finalAmount;
    }
}
