package com.telcobright.billing.mediation.rating;

import java.math.BigDecimal;

/**
 * What routesphere reserved for one tier in mem-ledger — the uom tells us package vs cash and
 * the amount is the hold to reconcile against the final charge.
 */
public record TierReserved(
        long PackageAccountId,
        String Uom,                 // TF_min | OTH_ea | BDT
        BigDecimal ReservedAmount) {
}
