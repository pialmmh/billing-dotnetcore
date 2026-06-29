package com.telcobright.billing.mediation.rating.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.PackageAccount;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.rating.BasicCharge;
import com.telcobright.billing.mediation.rating.CallFacts;
import com.telcobright.billing.mediation.rating.ITierRater;
import com.telcobright.billing.mediation.rating.ServiceType;
import com.telcobright.billing.mediation.rating.TierInput;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real GetMaxRatePerMinute tier rater: detect SG10 + match the customer rate over the RateCache
 * -&gt; a CASH candidate carrying the per-minute rate; eligible packages -&gt; package candidates (1 unit/min); no
 * service group -&gt; reject.
 */
class MaxRateTierRaterTests {
    private static MediationContext Sg10() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "2.5").idRatePlan(7));
        return f.mediation();
    }

    private static final Map<Integer, Partner> Retail5 = Map.of(5, new Partner(5, null, 3));

    private static CallFacts Facts() {
        return Facts("8801712345678");
    }

    private static CallFacts Facts(String called) {
        return new CallFacts("telcobright", 5, "8801999000111", called, "10.0.0.1", ServiceType.Voice, 0L);
    }

    private static ITierRater Rater() {
        return new MaxRateTierRater(BasicCharge.Default());
    }

    @Test
    void Detected_call_returns_a_cash_candidate_with_the_per_minute_rate() {
        var tier = new TierInput("telcobright", 5, Sg10(), List.of(), Retail5);
        var result = Rater().RateTier(Facts(), tier);

        assertEquals(10, result.ServiceGroupId());
        assertTrue(result.RejectReason().isEmpty());
        assertEquals(1, result.Candidates().size());
        var cash = result.Candidates().get(0);
        assertEquals("BDT", cash.Uom());
        assertEquals(2.5d, cash.RatePerMinute());
        assertEquals(2.5d, cash.MaxAmountFirstMinute());   // 60s @ 2.5/min
    }

    @Test
    void Eligible_packages_become_candidates_alongside_cash() {
        var packages = List.of(new PackageAccount(9, 5, "TF_min", null, null));
        var tier = new TierInput("telcobright", 5, Sg10(), packages, Retail5);
        var result = Rater().RateTier(Facts(), tier);

        assertEquals(2, result.Candidates().size());   // package + cash
        assertTrue(result.Candidates().stream().anyMatch(
                c -> c.PackageAccountId() == 9 && "TF_min".equals(c.Uom()) && c.MaxAmountFirstMinute() == 1d));
        assertTrue(result.Candidates().stream().anyMatch(
                c -> "BDT".equals(c.Uom()) && c.RatePerMinute() == 2.5d));
    }

    @Test
    void No_service_group_rejects() {
        // partner type 1 (not retail) -> SG10 not detected
        Map<Integer, Partner> partners = Map.of(5, new Partner(5, null, 1));
        var tier = new TierInput("telcobright", 5, Sg10(), List.of(), partners);
        var result = Rater().RateTier(Facts(), tier);

        assertEquals(0, result.ServiceGroupId());
        assertTrue(result.RejectReason().contains("service group"));
        assertTrue(result.Candidates().isEmpty());
    }
}
