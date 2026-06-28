using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Mediation.ServiceFamilies;

/// <summary>
/// A service family — the lean port of a legacy <c>IServiceFamily</c>'s CHARGE role. Given the matched
/// <see cref="rateassign"/> and the cdr, it computes the leg's charge + tax and returns the
/// <see cref="acc_chargeable"/> (the charge record the summary reads), mutating the relevant cdr fields.
///
/// SCOPE: this ports the family's RATING MATH only. The legacy families also do accounting/posting — GL
/// <c>postingAccount</c>, <c>BillingRule</c>, <c>acc_transaction</c>, <c>AutoIncrementManager</c> id,
/// <c>TelcobrightJob</c> bookkeeping — which is routesphere's mem-ledger domain + the batch scaffolding
/// Option B replaces; those are deferred. The family-internal service-tuple resolution (SF11's own
/// GetServiceTuple) is also deferred — the rate is matched upstream and handed in.
/// </summary>
public interface IServiceFamily
{
    int Id { get; }

    acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
        int maxDecimalPrecision);
}

/// <summary>Builds the common <see cref="acc_chargeable"/> shape from a family's computed charge + tax.
/// The GL/billing-rule/job fields (glAccountId, idBillingrule, createdByJob…) are left default — they
/// belong to the deferred accounting/posting slice.</summary>
internal static class ChargeableBuilder
{
    public static acc_chargeable Build(rateassign rate, cdr cdr, int serviceGroupId, int serviceFamilyId,
        AssignmentDirection direction, decimal billedAmount, decimal quantity, decimal tax) => new()
    {
        servicegroup = serviceGroupId,
        servicefamily = serviceFamilyId,
        assignedDirection = (sbyte)direction,
        BilledAmount = billedAmount,
        Quantity = quantity,
        TaxAmount1 = tax,
        unitPriceOrCharge = rate.rateamount,
        Prefix = rate.Prefix.ToString(),
        RateId = rate.id,
        idQuantityUom = "TF_s",
        uniqueBillId = cdr.UniqueBillId,
        idEvent = cdr.IdCall,
        transactionTime = cdr.StartTime,
    };

    public static decimal Round(decimal value, int maxDecimalPrecision) =>
        maxDecimalPrecision > 0 ? decimal.Round(value, maxDecimalPrecision) : value;
}
