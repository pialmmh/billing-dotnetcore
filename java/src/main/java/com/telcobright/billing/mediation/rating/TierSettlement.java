package com.telcobright.billing.mediation.rating;

import java.math.BigDecimal;

/**
 * One tier's final settlement. routesphere applies {@code Charged} to mem-ledger
 * (debit final, refund the rest of the hold). For a package uom the charge is in units
 * ({@code PackageAmount}); for cash (BDT) it is money ({@code InPartnerCost}).
 * {@code Tax} is the family's tax (SF10 VAT / SF11 BTRC). A non-null {@code Error} means
 * this tier could not be settled.
 */
public record TierSettlement(
        String DbName,
        int PartnerId,
        int ServiceGroupId,
        int ServiceFamilyId,
        String Uom,
        BigDecimal Charged,
        BigDecimal PackageAmount,      // billable minutes for package units (0 for cash)
        BigDecimal InPartnerCost,      // cash cost for BDT (0 for package units)
        BigDecimal Tax,                // family tax (SF10 VAT / SF11 BTRC)
        BigDecimal SupplierCost,       // out-partner cost from the supplier leg (admin FULL only; 0 otherwise)
        String MatchedPrefix,
        String Error) {

    public static TierSettlement Unrated(String dbName, int partnerId) {
        return new TierSettlement(dbName, partnerId, 0, 0, "", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "",
                "unrated: no service group, rate plan, or matching rate");
    }
}
