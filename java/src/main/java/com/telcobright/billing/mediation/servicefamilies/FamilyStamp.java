package com.telcobright.billing.mediation.servicefamilies;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.A2ZRateResult;

/**
 * The legacy A2ZRater-end leg stamping shared by the A2Z families: stamp the matched leg's prefix, rate,
 * partner cost, billed duration and country code onto the cdr (the CUSTOMER fields for direction Customer,
 * the SUPPLIER fields for direction Supplier).
 */
final class FamilyStamp {
    private FamilyStamp() {}

    static void StampLeg(cdr cdr, Rateext rate, AssignmentDirection direction, A2ZRateResult a2z) {
        if (direction == AssignmentDirection.Supplier) {
            cdr.MatchedPrefixSupplier = rate.Prefix;
            cdr.SupplierRate = rate.rateamount;
            cdr.OutPartnerCost = a2z.Amount();
            cdr.Duration2 = a2z.BilledDurationSec();
        } else {
            cdr.MatchedPrefixCustomer = rate.Prefix;
            cdr.CustomerRate = rate.rateamount;
            cdr.InPartnerCost = a2z.Amount();
            cdr.Duration1 = a2z.BilledDurationSec();
        }
        cdr.CountryCode = rate.CountryCode;
    }
}
