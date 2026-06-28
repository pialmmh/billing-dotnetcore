using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using MediationModel;

namespace Billing.Mediation.ServiceFamilies;

/// <summary>
/// SF 10 — the customer family for SG 10 (legacy <c>SfA2ZWithVatTax</c>). The charge is the basic A2Z
/// amount; the tax is <c>amount × OtherAmount3</c> (a VAT/tax fraction on the rate). Mutates the cdr's
/// Duration1 / InPartnerCost (customer) or OutPartnerCost (supplier) / CustomerRate / Tax1 (customer) or
/// Tax2 (supplier), matching the legacy family.
/// </summary>
public sealed class SfA2ZWithVatTax : IServiceFamily
{
    public int Id => 10;

    public acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
        int maxDecimalPrecision)
    {
        var a2z = A2ZRater.Rate(rate, cdr.DurationSec, billingSpanSec: 60, maxDecimalPrecision);
        cdr.Duration1 = a2z.BilledDurationSec;
        cdr.CustomerRate = rate.rateamount;

        if (direction == AssignmentDirection.Supplier) cdr.OutPartnerCost = a2z.Amount;
        else cdr.InPartnerCost = a2z.Amount;

        // tax = charge × OtherAmount3 (the legacy SfA2ZWithVatTax multiplies by OtherAmount3 directly —
        // i.e. OtherAmount3 is a fraction, e.g. 0.15 — NOT a percent /100 like the base SfA2Z).
        var tax = ChargeableBuilder.Round(a2z.Amount * (decimal)(rate.OtherAmount3 ?? 0f), maxDecimalPrecision);
        if (direction == AssignmentDirection.Supplier) cdr.Tax2 = tax;
        else cdr.Tax1 = tax;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id, direction,
            billedAmount: a2z.Amount, quantity: a2z.BilledDurationSec, tax: tax);
    }
}
