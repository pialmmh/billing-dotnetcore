package com.telcobright.billing.mediation.rating;

/**
 * One ranked option for a tier. routesphere reserves the first FUNDED candidate in
 * mem-ledger. For a package: max_amount_first_minute = 1 unit; for cash: the per-minute rate.
 */
public record RateCandidate(
        long PackageAccountId,
        String Uom,                 // TF_min | OTH_ea | BDT
        double RatePerMinute,
        double MaxAmountFirstMinute) {
}
