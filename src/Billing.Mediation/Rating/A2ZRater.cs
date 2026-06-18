using MediationModel;

namespace Billing.Mediation.Rating;

/// <summary>The A2Z charge for one leg over a matched <see cref="rateassign"/>: pulse/surcharge-adjusted
/// billed duration + the amount.</summary>
public readonly record struct A2ZRateResult(decimal BilledDurationSec, decimal Amount);

/// <summary>
/// Faithful port of legacy <c>A2ZRater.ExecuteA2ZRating</c> (the duration + amount branching) over a
/// <see cref="rateassign"/>. Two surcharge modes:
/// <list type="bullet">
/// <item><c>SurchargeTime == 0</c>: bill the pulse-rounded whole duration.</item>
/// <item><c>SurchargeTime &gt; 0</c>: the first <c>SurchargeTime</c> seconds are a MINIMUM initial period
///   (billed in full, not pulse-rounded); the remainder is pulse-rounded and added.</item>
/// </list>
/// In every case the amount is <c>billedDuration × (rateamount / billingSpan)</c> — the legacy
/// <c>GetA2ZAmountWithSurCharge</c>/<c>WithOutSurCharge</c> reduce to this (the surcharge split is at the
/// same rate). The flat <c>SurchargeAmount</c> field is NOT used by this model. <c>billingSpanSec</c> is
/// 60 (per-minute) until the rate plan's BillingSpan is wired; <c>RateAmountRoundupDecimal</c> (rate-plan
/// level) is deferred — pass-throughs noted.
/// </summary>
public static class A2ZRater
{
    public static A2ZRateResult Rate(rateassign rate, decimal actualDurationSec,
        int billingSpanSec = 60, int maxDecimalPrecision = 8)
    {
        var billedDuration = BilledDuration(rate, actualDurationSec);
        var amount = Amount(rate, billedDuration, billingSpanSec, maxDecimalPrecision);
        return new A2ZRateResult(billedDuration, amount);
    }

    /// <summary>The billed duration under the surcharge/pulse model (legacy ExecuteA2ZRating).</summary>
    public static decimal BilledDuration(rateassign rate, decimal actualDurationSec)
    {
        if (actualDurationSec == 0) return 0;

        if (rate.SurchargeTime == 0)
            return A2ZDuration(actualDurationSec, rate);                      // whole duration, pulse-rounded

        if (actualDurationSec <= rate.SurchargeTime)
            return rate.SurchargeTime;                                        // minimum initial period

        // initial period (full) + pulse-rounded remainder. (Legacy left finalDuration 0 in this branch —
        // a latent bug; we record the actual billed total.)
        return rate.SurchargeTime + A2ZDuration(actualDurationSec - rate.SurchargeTime, rate);
    }

    /// <summary>Pulse-adjusted duration (legacy PrefixMatcher.GetA2ZDuration). MinDurationSec is a
    /// millisecond-rounding threshold (&lt;0 = actual, &gt;0 = ceil if frac ≥ threshold else floor, =0 =
    /// always ceil), then ceil up to the Resolution (pulse) multiple.</summary>
    public static decimal A2ZDuration(decimal actualDurationSec, rateassign rate)
    {
        if (actualDurationSec == 0) return 0;

        var minDurationSec = (decimal)rate.MinDurationSec;
        if (minDurationSec < 0M) return actualDurationSec;

        if (minDurationSec > 0M)
        {
            var frac = actualDurationSec - decimal.Floor(actualDurationSec);
            actualDurationSec = frac >= minDurationSec ? decimal.Ceiling(actualDurationSec)
                                                       : decimal.Floor(actualDurationSec);
        }
        else
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

    private static decimal Amount(rateassign rate, decimal billedDurationSec, int billingSpanSec, int maxDecimalPrecision)
    {
        if (billedDurationSec == 0 || billingSpanSec <= 0) return 0;
        var amount = billedDurationSec * (rate.rateamount / billingSpanSec);
        return maxDecimalPrecision > 0 ? decimal.Round(amount, maxDecimalPrecision) : amount;
    }
}
