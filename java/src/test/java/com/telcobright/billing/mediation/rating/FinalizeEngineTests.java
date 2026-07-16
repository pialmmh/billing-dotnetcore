package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The finalize compute core over the legacy entities: per-tier settlement (detect -&gt; resolve -&gt;
 * PrefixMatcher -&gt; A2ZRater), cash vs package uom split, multi-tier totals, unrated tiers, and the
 * unanswered / empty-chain edges.
 */
class FinalizeEngineTests {
    private static MediationContext Mediation() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(8801712, "1.0").idRatePlan(7));
        return f.mediation();
    }

    // adds a SG10 supplier tuple (out-partner 7 @ 2.0/min) on top of the customer tuple.
    private static MediationContext MediationWithSupplier() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(8801712, "1.0").idRatePlan(7));
        f.tup(10, AssignmentDirection.Supplier.value, 7, null, 0, TestData.Ra(8801712, "2.0").idRatePlan(8));
        return f.mediation();
    }

    private static final Map<Integer, Partner> RetailPartner5 = Map.of(5, new Partner(5, null, 3));

    private static FinalizeFacts Facts(String called, int billsec, boolean answered, int outPartnerId) {
        return new FinalizeFacts("telcobright", "8801999000111", called, ServiceType.Voice, 1, "in", "out",
                outPartnerId, LocalDateTime.of(2026, 6, 19, 0, 0, 0), billsec, answered, "uid-1");
    }

    private static FinalizeFacts Facts() {
        return Facts("8801712345678", 60, true, 0);
    }

    private static FinalizeFacts Facts(String called) {
        return Facts(called, 60, true, 0);
    }

    private static FinalizeFacts Facts(int outPartnerId) {
        return Facts("8801712345678", 60, true, outPartnerId);
    }

    private static FinalizeFacts Facts(int billsec, boolean answered) {
        return Facts("8801712345678", billsec, answered, 0);
    }

    private static FinalizeTierInput Tier(String dbName, TierMode mode, TierReserved reserved) {
        return new FinalizeTierInput(dbName, 5, Mediation(), RetailPartner5, mode, reserved);
    }

    @Test
    void Cash_tier_settles_to_the_a2z_amount() {
        var result = FinalizeEngine.Default().Finalize(Facts(),
                List.of(Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", new BigDecimal("2.0")))));

        assertTrue(result.Success());
        var s = result.Settlements().get("telcobright");
        assertEquals(10, s.ServiceGroupId());
        assertEquals(10, s.ServiceFamilyId());
        assertEquals(0, new BigDecimal("1.0").compareTo(s.Charged()));
        assertEquals(0, new BigDecimal("1.0").compareTo(s.InPartnerCost()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.PackageAmount()));
        assertEquals("8801712", s.MatchedPrefix());
        assertEquals(0, new BigDecimal("1.0").compareTo(result.TotalCharged()));
    }

    @Test
    void Package_tier_settles_to_consumed_minutes() {
        var result = FinalizeEngine.Default().Finalize(Facts(),
                List.of(Tier("res_233", TierMode.CustomerOnly, new TierReserved(200, "TF_min", new BigDecimal("5.0")))));

        var s = result.Settlements().get("res_233");
        assertEquals("TF_min", s.Uom());
        assertEquals(0, new BigDecimal("1.0").compareTo(s.PackageAmount()));     // 60s -> 1 minute
        assertEquals(0, new BigDecimal("1.0").compareTo(s.Charged()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.InPartnerCost()));
    }

    @Test
    void Multi_tier_settles_each_and_sums_the_total() {
        var result = FinalizeEngine.Default().Finalize(Facts(),
                List.of(
                        Tier("res_233", TierMode.CustomerOnly, new TierReserved(200, "BDT", new BigDecimal("2.0"))),
                        Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", new BigDecimal("2.0")))));

        assertTrue(result.Success());
        assertEquals(2, result.Settlements().size());
        assertEquals(0, new BigDecimal("2.0").compareTo(result.TotalCharged()));
    }

    @Test
    void Unrated_tier_fails_the_call() {
        // 8809999999 normalizes to 9999999 — no matching rateassign prefix.
        var result = FinalizeEngine.Default().Finalize(Facts("8809999999"),
                List.of(Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", new BigDecimal("2.0")))));

        assertFalse(result.Success());
        assertNotNull(result.Settlements().get("telcobright").Error());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.TotalCharged()));
    }

    @Test
    void Empty_chain_is_an_unknown_tenant() {
        var result = FinalizeEngine.Default().Finalize(Facts(), List.of());
        assertFalse(result.Success());
        assertTrue(result.Error().contains("unknown tenant"));
        assertTrue(result.Settlements().isEmpty());
    }

    @Test
    void Full_tier_also_charges_the_supplier_leg() {
        var tier = new FinalizeTierInput("telcobright", 5, MediationWithSupplier(), RetailPartner5,
                TierMode.Full, new TierReserved(100, "BDT", new BigDecimal("5.0")));

        var result = FinalizeEngine.Default().Finalize(Facts(7), List.of(tier));

        var s = result.Settlements().get("telcobright");
        assertEquals(0, new BigDecimal("1.0").compareTo(s.Charged()));        // customer leg (60s @ 1.0/min)
        assertEquals(0, new BigDecimal("2.0").compareTo(s.SupplierCost()));   // supplier leg (60s @ 2.0/min, out-partner 7)
    }

    @Test
    void Customer_only_tier_has_no_supplier_cost() {
        var result = FinalizeEngine.Default().Finalize(Facts(7),
                List.of(Tier("res_233", TierMode.CustomerOnly, new TierReserved(100, "BDT", new BigDecimal("5.0")))));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.Settlements().get("res_233").SupplierCost()));
    }

    @Test
    void Unanswered_call_settles_to_zero_without_error() {
        var result = FinalizeEngine.Default().Finalize(Facts(0, false),
                List.of(Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", BigDecimal.ZERO))));

        assertTrue(result.Success());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.Settlements().get("telcobright").Charged()));
        assertEquals(0, BigDecimal.ZERO.compareTo(result.TotalCharged()));
    }
}
