package com.telcobright.billing.mediation.rating;

/**
 * Rates one tier: detect the service group (-&gt; category) from the tier's MediationContext and
 * the called number, then rank that tier's eligible package/cash candidates. Swapped from stub to the
 * real Sg/Sf/A2ZRater-backed implementation once config-manager serves the MediationContext.
 */
public interface ITierRater {
    TierRating RateTier(CallFacts facts, TierInput tier);
}
