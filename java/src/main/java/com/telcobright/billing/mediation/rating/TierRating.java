package com.telcobright.billing.mediation.rating;

import java.util.List;

/**
 * One tier's rating: the charged partner, the detected service group (category), the ranked
 * candidates, and a reject reason (non-empty =&gt; this tier rejects the call).
 */
public record TierRating(
        String DbName,
        int PartnerId,
        int ServiceGroupId,
        String RejectReason,
        List<RateCandidate> Candidates) {
}
