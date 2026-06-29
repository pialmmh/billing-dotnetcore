package com.telcobright.billing.mediation.rating;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * The whole per-call settlement: success + per-tier settlements keyed by dbName (globally
 * unique) + the total charged across tiers.
 */
public record FinalizeResult(
        boolean Success,
        String Error,
        Map<String, TierSettlement> Settlements,
        BigDecimal TotalCharged) {

    public static FinalizeResult Fail(String error) {
        return new FinalizeResult(false, error, new HashMap<>(), BigDecimal.ZERO);
    }
}
