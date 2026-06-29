package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.rating.A2ZRateResult;

import java.math.BigDecimal;

/**
 * SF 10 — the customer family for SG 10 (legacy {@code SfA2ZWithVatTax}). The charge is the basic A2Z
 * amount; the tax is {@code amount × OtherAmount3} (a VAT/tax fraction on the rate). Mutates the cdr's
 * Duration1 / InPartnerCost (customer) or OutPartnerCost (supplier) / CustomerRate / Tax1 (customer) or
 * Tax2 (supplier), matching the legacy family.
 */
public final class SfA2ZWithVatTax implements IServiceFamily {
    @Override public int Id() { return 10; }

    @Override
    public acc_chargeable Charge(rateassign rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            int maxDecimalPrecision) {
        A2ZRateResult a2z = A2ZRater.Rate(rate, cdr.DurationSec, 60, maxDecimalPrecision);
        cdr.Duration1 = a2z.BilledDurationSec();
        cdr.CustomerRate = rate.rateamount;

        if (direction == AssignmentDirection.Supplier) cdr.OutPartnerCost = a2z.Amount();
        else cdr.InPartnerCost = a2z.Amount();

        // tax = charge × OtherAmount3 (the legacy SfA2ZWithVatTax multiplies by OtherAmount3 directly —
        // i.e. OtherAmount3 is a fraction, e.g. 0.15 — NOT a percent /100 like the base SfA2Z).
        var tax = ChargeableBuilder.Round(
                a2z.Amount().multiply(new BigDecimal(Float.toString(rate.OtherAmount3 != null ? rate.OtherAmount3 : 0f))),
                maxDecimalPrecision);
        if (direction == AssignmentDirection.Supplier) cdr.Tax2 = tax;
        else cdr.Tax1 = tax;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), direction,
                a2z.Amount(), a2z.BilledDurationSec(), tax);
    }
}
