package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.Rule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service-group-wise CONFIGURED rating rules (legacy ExecuteRating): {@code BasicCharge.Rate} runs the detected
 * SG's configured RatingRules — each names a family (by id) + direction — over the per-day RateCache. These
 * prove the family/direction set is CONFIG-DRIVEN, not hardcoded: a disabled SG charges nothing, a swapped
 * family id changes the family that runs, and two rules yield two chargeables (customer + supplier).
 */
class ServiceGroupRatingRulesTests {

    private static final Map<Integer, Partner> Retail5 = Map.of(5, new Partner(5, null, 3));

    private static cdr Sg10Cdr() {
        cdr c = new cdr();
        c.InPartnerId = 5;
        c.OutPartnerId = 7;
        c.TerminatingCalledNumber = "8801712345678";
        c.DurationSec = BigDecimal.valueOf(60);
        c.StartTime = LocalDateTime.of(2026, 6, 19, 0, 0);
        c.AnswerTime = LocalDateTime.of(2026, 6, 19, 0, 0);
        c.ChargingStatus = 1;
        c.UniqueBillId = "uid-1";
        return c;
    }

    // SG10 customer tuple: per-minute 1.0 for prefix 1712 (partner 5, rate plan 7).
    private static TestData.Fixture CustFixture() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "1.0").idRatePlan(7));
        return f;
    }

    // …plus a SG10 supplier tuple: 2.0 for prefix 1712 (out-partner 7, rate plan 8).
    private static TestData.Fixture CustSuppFixture() {
        var f = CustFixture();
        f.tup(10, AssignmentDirection.Supplier.value, 7, null, 0, TestData.Ra(1712, "2.0").idRatePlan(8));
        return f;
    }

    private static Map<Integer, ServiceGroupConfiguration> Sg10(boolean disabled, RatingRule... rules) {
        List<Rule> ruleList = new ArrayList<>();
        for (RatingRule r : rules) ruleList.add(r);
        return Map.of(10, new ServiceGroupConfiguration(10, disabled, ruleList, null, null));
    }

    @Test
    void Default_config_runs_the_sg10_customer_rule() {
        var med = CustFixture().mediation();   // built-in default SG configs
        var chargeables = BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5);

        assertEquals(1, chargeables.size());
        assertEquals(10, chargeables.get(0).servicefamily);   // SF10 customer (supplier rule finds no tuple)
    }

    @Test
    void Two_configured_rules_yield_a_customer_and_a_supplier_leg() {
        var med = CustSuppFixture().mediation();   // default: SF10 cust + SF1 supplier
        var chargeables = BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5);

        assertEquals(2, chargeables.size());
        assertTrue(chargeables.stream().anyMatch(c -> c.servicefamily == 10 && c.assignedDirection == 1));   // customer
        assertTrue(chargeables.stream().anyMatch(c -> c.servicefamily == 1 && c.assignedDirection == 2));    // supplier
    }

    @Test
    void Family_is_taken_from_config_not_hardcoded() {
        // configure SG10's customer rule to run SF11 instead of SF10
        var med = CustFixture().mediation(null, null, Sg10(false, new RatingRule(11, 1, null)), null);
        var chargeables = BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5);

        assertEquals(1, chargeables.size());
        assertEquals(11, chargeables.get(0).servicefamily);   // SF11, because config said so
    }

    @Test
    void Disabled_service_group_charges_nothing() {
        var med = CustFixture().mediation(null, null, Sg10(true, new RatingRule(10, 1, null)), null);

        assertTrue(BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5).isEmpty());
        assertNull(BasicCharge.Default().Compute(Sg10Cdr(), AssignmentDirection.Customer, med, Retail5));
    }
}
