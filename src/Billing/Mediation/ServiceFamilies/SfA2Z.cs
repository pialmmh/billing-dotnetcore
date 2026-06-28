using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using MediationModel;

namespace Billing.Mediation.ServiceFamilies;

/// <summary>
/// SF 1 — the base A2Z family (legacy <c>SfA2Z</c>), used as SG10's SUPPLIER leg (the cost paid to the
/// out-partner). The charge is the basic A2Z amount over the supplier rate; the tax is
/// <c>InPartnerCost × OtherAmount3 / 100</c>.
///
/// FIDELITY NOTE: the legacy <c>SetTaxAmount</c> always multiplies by <c>cdr.InPartnerCost</c> (the
/// CUSTOMER cost set by the prior customer leg), even in the supplier direction — so the supplier leg
/// MUST run after the customer leg on the SAME cdr. The supplier leg writes OutPartnerCost / SupplierRate
/// / Duration2 / Tax2; the customer leg writes InPartnerCost / CustomerRate / Duration1 / Tax1.
/// </summary>
public sealed class SfA2Z : IServiceFamily
{
    public int Id => 1;

    public acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
        int maxDecimalPrecision)
    {
        var a2z = A2ZRater.Rate(rate, cdr.DurationSec, billingSpanSec: 60, maxDecimalPrecision);

        if (direction == AssignmentDirection.Supplier)
        {
            cdr.OutPartnerCost = a2z.Amount;
            cdr.SupplierRate = rate.rateamount;
            cdr.Duration2 = a2z.BilledDurationSec;
        }
        else
        {
            cdr.InPartnerCost = a2z.Amount;
            cdr.CustomerRate = rate.rateamount;
            cdr.Duration1 = a2z.BilledDurationSec;
        }

        // legacy SfA2Z.SetTaxAmount: tax = InPartnerCost * OtherAmount3 / 100 (always InPartnerCost).
        var tax = ChargeableBuilder.Round(
            (decimal)(cdr.InPartnerCost ?? 0m) * (decimal)(rate.OtherAmount3 ?? 0f) / 100m, maxDecimalPrecision);
        if (direction == AssignmentDirection.Supplier) cdr.Tax2 = tax; else cdr.Tax1 = tax;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id, direction,
            billedAmount: a2z.Amount, quantity: a2z.BilledDurationSec, tax: tax);
    }
}
