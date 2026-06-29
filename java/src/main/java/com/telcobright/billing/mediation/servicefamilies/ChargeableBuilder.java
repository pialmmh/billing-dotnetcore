package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Builds the common {@link acc_chargeable} shape from a family's computed charge + tax.
 * The GL/billing-rule/job fields (glAccountId, idBillingrule, createdByJob…) are left default — they
 * belong to the deferred accounting/posting slice.
 */
final class ChargeableBuilder {

    private ChargeableBuilder() {}

    static acc_chargeable Build(Rateext rate, cdr cdr, int serviceGroupId, int serviceFamilyId,
            AssignmentDirection direction, BigDecimal billedAmount, BigDecimal quantity, BigDecimal tax) {
        var c = new acc_chargeable();
        c.servicegroup = serviceGroupId;
        c.servicefamily = serviceFamilyId;
        c.assignedDirection = (byte) direction.value;
        c.BilledAmount = billedAmount;
        c.Quantity = quantity;
        c.TaxAmount1 = tax;
        c.unitPriceOrCharge = rate.rateamount;
        c.Prefix = rate.Prefix;
        c.RateId = rate.id;
        c.idQuantityUom = "TF_s";
        c.uniqueBillId = cdr.UniqueBillId;
        c.idEvent = cdr.IdCall;
        c.transactionTime = cdr.StartTime;
        return c;
    }

    static BigDecimal Round(BigDecimal value, int maxDecimalPrecision) {
        return maxDecimalPrecision > 0 ? value.setScale(maxDecimalPrecision, RoundingMode.HALF_EVEN) : value;
    }
}
