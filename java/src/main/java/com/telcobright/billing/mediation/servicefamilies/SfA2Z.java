package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.rating.A2ZRateResult;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * SF 1 — the base A2Z family (legacy {@code SfA2Z}), used as SG10's SUPPLIER leg (the cost paid to the
 * out-partner). The charge is the A2Z amount over the supplier rate; the tax is
 * {@code InPartnerCost × OtherAmount3 / 100}.
 *
 * <p>FIDELITY NOTE: the legacy {@code SetTaxAmount} always multiplies by {@code cdr.InPartnerCost} (the
 * CUSTOMER cost set by the prior customer leg), even in the supplier direction — so the supplier leg MUST
 * run after the customer leg on the SAME cdr. The supplier leg stamps MatchedPrefixSupplier / SupplierRate /
 * OutPartnerCost / Duration2 / CountryCode / Tax2 (legacy A2ZRater end + the family).
 */
public final class SfA2Z implements IServiceFamily {
    @Override public int Id() { return 1; }

    @Override
    public acc_chargeable Charge(Rateext rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            MediationContext mediation) {
        int maxDecimalPrecision = mediation.MaxDecimalPrecision;
        A2ZRateResult a2z = A2ZRater.Rate(rate, cdr.DurationSec, mediation.DicRatePlan, mediation.BillingSpans, maxDecimalPrecision);
        FamilyStamp.StampLeg(cdr, rate, direction, a2z);

        // legacy SfA2Z.SetTaxAmount: tax = InPartnerCost * OtherAmount3 / 100 (always InPartnerCost).
        var inPartnerCost = cdr.InPartnerCost != null ? cdr.InPartnerCost : BigDecimal.ZERO;
        var otherAmount3 = rate.OtherAmount3 != null ? rate.OtherAmount3 : BigDecimal.ZERO;
        var tax = ChargeableBuilder.Round(
                inPartnerCost.multiply(otherAmount3).divide(BigDecimal.valueOf(100), MathContext.DECIMAL128),
                maxDecimalPrecision);
        if (direction == AssignmentDirection.Supplier) cdr.Tax2 = tax; else cdr.Tax1 = tax;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), direction,
                a2z.Amount(), a2z.BilledDurationSec(), tax);
    }
}
