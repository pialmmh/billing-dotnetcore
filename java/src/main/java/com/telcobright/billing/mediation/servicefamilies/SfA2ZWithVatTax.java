package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRater;
import com.telcobright.billing.mediation.rating.A2ZRateResult;

import java.math.BigDecimal;

/**
 * SF 10 — the customer family for SG 10 (legacy {@code SfA2ZWithVatTax}). The charge is the A2Z amount; the
 * tax is {@code amount × OtherAmount3} (a VAT/tax fraction on the charge — OtherAmount3 is a fraction, e.g.
 * 0.15, NOT a percent /100 like the base SfA2Z). Stamps the matched leg (legacy A2ZRater end + the family):
 * MatchedPrefix* / *Rate / *PartnerCost / Duration1|2 / CountryCode / Tax1|2.
 */
public final class SfA2ZWithVatTax implements IServiceFamily {
    @Override public int Id() { return 10; }

    @Override
    public acc_chargeable Charge(Rateext rate, cdr cdr, int serviceGroupId, AssignmentDirection direction,
            MediationContext mediation) {
        int maxDecimalPrecision = mediation.MaxDecimalPrecision;
        A2ZRateResult a2z = A2ZRater.Rate(rate, cdr.DurationSec, mediation.DicRatePlan, mediation.BillingSpans, maxDecimalPrecision);
        FamilyStamp.StampLeg(cdr, rate, direction, a2z);

        var otherAmount3 = rate.OtherAmount3 != null ? rate.OtherAmount3 : BigDecimal.ZERO;
        var tax = ChargeableBuilder.Round(a2z.Amount().multiply(otherAmount3), maxDecimalPrecision);
        if (direction == AssignmentDirection.Supplier) cdr.Tax2 = tax; else cdr.Tax1 = tax;

        return ChargeableBuilder.Build(rate, cdr, serviceGroupId, Id(), direction,
                a2z.Amount(), a2z.BilledDurationSec(), tax, mediation);
    }
}
