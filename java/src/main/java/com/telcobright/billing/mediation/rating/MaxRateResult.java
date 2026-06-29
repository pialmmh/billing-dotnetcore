package com.telcobright.billing.mediation.rating;

import java.util.HashMap;
import java.util.Map;

/**
 * The whole multi-tier result: ok + per-tier ratings keyed by dbName (globally unique).
 */
public record MaxRateResult(
        boolean Ok,
        String RejectReason,
        Map<String, TierRating> Tiers) {

    public static MaxRateResult Reject(String reason) {
        return new MaxRateResult(false, reason, new HashMap<>());
    }
}
