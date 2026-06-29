package com.telcobright.billing.mediation.rating.internal;

import com.telcobright.billing.mediation.rating.CallFacts;
import com.telcobright.billing.mediation.rating.ITierRater;
import com.telcobright.billing.mediation.rating.RateCandidate;
import com.telcobright.billing.mediation.rating.TierInput;
import com.telcobright.billing.mediation.rating.TierRating;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Placeholder rater that proves the traversal + contract end-to-end while the real MediationContext
 * is still being built (Task C, config-manager). It does NOT detect service groups or compute prices;
 * it echoes the tier's eligible package accounts as zero-rated candidates (or one cash placeholder),
 * so the per-tier map carries real shape. Replaced by the Sg/Sf/A2ZRater-backed rater once the
 * MediationContext (categories + detection rules + today's rates) lands.
 */
public final class StubTierRater implements ITierRater {

    @Override
    public TierRating RateTier(CallFacts facts, TierInput tier) {
        List<RateCandidate> candidates = tier.Packages().size() > 0
                ? tier.Packages().stream().map(p -> new RateCandidate(p.Id(), p.Uom() != null ? p.Uom() : "", 0d, 0d)).collect(Collectors.toList())
                : List.of(new RateCandidate(0, "BDT", 0d, 0d));

        return new TierRating(
                tier.DbName(),
                tier.PartnerId(),
                0,                        // 0 = not detected yet (stub)
                "",                       // stub never rejects; real rater rejects on no-SG / no-rate / no-package
                candidates);
    }
}
