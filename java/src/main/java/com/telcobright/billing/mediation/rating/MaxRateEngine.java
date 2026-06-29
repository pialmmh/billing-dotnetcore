package com.telcobright.billing.mediation.rating;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Traverses the entry tenant's chain (leaf-&gt;root) and rates each tier, returning the per-tier map
 * keyed by dbName. This is the body of GetMaxRatePerMinute, minus the gRPC and the registry lookup
 * (the caller resolves the chain and hands it in). Per-tier rating is delegated to {@link ITierRater}.
 */
public final class MaxRateEngine {
    private final ITierRater _rater;

    public MaxRateEngine(ITierRater rater) {
        this._rater = rater;
    }

    public MaxRateResult Resolve(CallFacts facts, List<TierInput> chain) {
        if (chain.isEmpty())
            return MaxRateResult.Reject("unknown tenant '" + facts.Tenant() + "'");

        var tiers = new LinkedHashMap<String, TierRating>(chain.size());
        for (var tier : chain)
            tiers.put(tier.DbName(), _rater.RateTier(facts, tier));

        var rejecting = tiers.values().stream()
                .filter(t -> t.RejectReason() != null && !t.RejectReason().isEmpty())
                .findFirst().orElse(null);
        return new MaxRateResult(rejecting == null, rejecting != null ? rejecting.RejectReason() : "", tiers);
    }
}
