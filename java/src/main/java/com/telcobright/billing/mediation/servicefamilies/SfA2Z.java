package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.rating.A2ZRateResult;

import java.math.BigDecimal;

/**
 * SF 1 — the base A2Z family (legacy {@code SfA2Z}), used as SG10's SUPPLIER leg (the cost paid to the
 * out-partner). The charge is the basic A2Z amount over the supplier rate; the tax is
 * {@code InPartnerCost × OtherAmount3 / 100}.
 *
 * <p>FIDELITY NOTE: the legacy {@code SetTaxAmount} always multiplies by {@code cdr.InPartnerCost} (the
 * CUSTOMER cost set by the prior customer leg), even in the supplier direction — so the supplier leg
 * MUST run after the customer leg on the SAME cdr. The supplier leg writes OutPartnerCost / SupplierRate
 * / Duration2 / Tax2; the customer leg writes InPartnerCost / CustomerRate / Duration1 / Tax1.
 */
public final class SfA2Z implements IServiceFamily {
    @Override public int Id() { return 1; }

    @Override
    public acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            int maxDecimalPrecision) {
        A2ZRateResult a2z = A2ZRater.Rate(rate, cdr.DurationSec, 60, maxDecimalPrecision);

        if (direction == AssignmentDirection.Supplier) {
            cdr.OutPartnerCost = a2z.Amount();
            cdr.SupplierRate = rate.rateamount;
            cdr.Duration2 = a2z.BilledDurationSec();
        } else {
            cdr.InPartnerCost = a2z.Amount();
            cdr.CustomerRate = rate.rateamount;
            cdr.Duration1 = a2z.BilledDurationSec();
        }

        // legacy SfA2Z.SetTaxAmount: tax = InPartnerCost * OtherAmount3 / 100 (always InPartnerCost).
        var tax = ChargeableBuilder.Round(
                (cdr.InPartnerCost != null ? cdr.InPartnerCost : BigDecimal.ZERO)
                        .multiply(new BigDecimal(Float.toString(rate.OtherAmount3 != null ? rate.OtherAmount3 : 0f)))
                        .divide(BigDecimal.valueOf(100)),
                maxDecimalPrecision);
        if (direction == AssignmentDirection.Supplier) cdr.Tax2 = tax; else cdr.Tax1 = tax;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), direction,
                a2z.Amount(), a2z.BilledDurationSec(), tax);
    }
}
