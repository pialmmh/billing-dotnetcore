using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using MediationModel;

namespace Billing.Mediation.ServiceFamilies;

/// <summary>
/// SF 11 — the customer family for SG 11 (legacy <c>SfDomOffNetInAns</c>, the domestic-incoming ANS
/// charge). NOT the basic A2Z amount — it recomputes a termination (ANS) charge:
/// <list type="bullet">
/// <item>billed duration = pulse-rounded actual duration;</item>
/// <item>effective rate = <c>rateamount − OtherAmount1</c> (OtherAmount1 = the IOF/additional charge);</item>
/// <item>ANS amount = <c>billedDuration × effectiveRate / 60</c>;</item>
/// <item>BTRC tax = <c>ANS amount × OtherAmount3</c> (OtherAmount3 = the BTRC fraction).</item>
/// </list>
/// The chargeable's BilledAmount is the ANS amount; TaxAmount1 is the BTRC. Mutates the cdr's
/// Tax1/RevenueIgwOut/CountryCode/MatchedPrefixSupplier/RoundedDuration/CustomerRate as the legacy did.
/// (Legacy used a ceiling-style RoundFractionsUpTo; this uses decimal.Round — fidelity note.)
/// </summary>
public sealed class SfDomOffNetInAns : IServiceFamily
{
    public int Id => 11;

    public acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
        int maxDecimalPrecision)
    {
        var billedDuration = A2ZRater.A2ZDuration(cdr.DurationSec, rate);

        var iof = ChargeableBuilder.Round((decimal)(rate.OtherAmount1 ?? 0f), maxDecimalPrecision);
        var effectiveRate = rate.rateamount - iof;
        var ansAmount = ChargeableBuilder.Round(billedDuration * effectiveRate / 60m, maxDecimalPrecision);

        var btrcFraction = ChargeableBuilder.Round((decimal)(rate.OtherAmount3 ?? 0f), maxDecimalPrecision);
        var btrcAmount = ChargeableBuilder.Round(ansAmount * btrcFraction, maxDecimalPrecision);

        cdr.Tax1 = btrcAmount;
        cdr.RevenueIgwOut = ansAmount;
        cdr.CountryCode = rate.CountryCode;
        cdr.MatchedPrefixSupplier = rate.Prefix.ToString();
        cdr.RoundedDuration = billedDuration;
        cdr.CustomerRate = rate.rateamount;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id, direction,
            billedAmount: ansAmount, quantity: billedDuration, tax: btrcAmount);
    }
}
