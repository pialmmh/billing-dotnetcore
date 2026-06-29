package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.model.PackageAccount;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.rating.internal.StubTierRater;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The multi-tier traversal + contract: a 3-tier chain yields a 3-entry map keyed by dbName,
 * each tier carrying candidates; an empty chain (unknown tenant) rejects. Uses the stub rater.
 */
class MaxRateEngineTests {
    private static final CallFacts Facts =
            new CallFacts("res_205", 101, "8801000", "8801999", "10.0.0.1", ServiceType.Voice, 0L);

    private static final Map<Integer, Partner> NoPartners = Map.of();

    private static MaxRateEngine NewEngine() {
        return new MaxRateEngine(new StubTierRater());
    }

    @Test
    void Chain_yields_one_tier_per_db_name_with_candidates() {
        var med = MediationContext.Empty;
        var chain = List.of(
                new TierInput("res_205", 101, med,
                        List.of(new PackageAccount(9, 101, "TF_min", null, null)), NoPartners),
                new TierInput("res_203", 0, med, List.of(), NoPartners),
                new TierInput("telcobright", 0, med, List.of(), NoPartners));

        var result = NewEngine().Resolve(Facts, chain);

        assertTrue(result.Ok());
        assertEquals(3, result.Tiers().size());
        assertTrue(result.Tiers().containsKey("res_205"));
        assertTrue(result.Tiers().containsKey("res_203"));
        assertTrue(result.Tiers().containsKey("telcobright"));
        // leaf with a package -> that package becomes the candidate
        var leaf = result.Tiers().get("res_205");
        assertEquals(101, leaf.PartnerId());
        assertEquals(1, leaf.Candidates().size());
        assertEquals(9, leaf.Candidates().get(0).PackageAccountId());
        assertEquals("TF_min", leaf.Candidates().get(0).Uom());
        // tier with no packages -> one cash placeholder
        var tbCandidates = result.Tiers().get("telcobright").Candidates();
        assertEquals(1, tbCandidates.size());
        assertEquals("BDT", tbCandidates.get(0).Uom());
    }

    @Test
    void Empty_chain_rejects() {
        var result = NewEngine().Resolve(Facts, List.of());

        assertFalse(result.Ok());
        assertTrue(result.Tiers().isEmpty());
        assertTrue(result.RejectReason().contains("unknown tenant"));
    }
}
