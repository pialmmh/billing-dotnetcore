package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The customer-leg charge end to end over the legacy entities: detect SG -&gt; resolve the tuple -&gt;
 * PrefixMatcher -&gt; the SG's service family -&gt; acc_chargeable. Proves the whole rating chain wires together
 * over a MediationContext.
 */
class BasicChargeTests {

    // SG10 customer tuple for partner 5: per-minute 1.0 for prefix 1712, rate plan 7.
    private static MediationContext Mediation() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(8801712, "1.0").idRatePlan(7));
        return f.mediation();
    }

    private static Map<Integer, Partner> Partners(Partner... ps) {
        Map<Integer, Partner> m = new HashMap<>();
        for (Partner p : ps) m.put(p.IdPartner(), p);
        return m;
    }

    @Test
    void Charges_a_detected_call_end_to_end() {
        var thisCdr = new cdr();
        thisCdr.InPartnerId = 5;
        thisCdr.TerminatingCalledNumber = "8801712345678"; thisCdr.OriginatingCalledNumber = "8801712345678";
        thisCdr.DurationSec = new BigDecimal("60");
        var chargeable = BasicCharge.Default().Compute(
                thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner(5, null, 3)));

        assertNotNull(chargeable);
        assertEquals(10, chargeable.servicegroup);     // retail -> SG10
        assertEquals(10, chargeable.servicefamily);    // SF10
        assertEquals("8801712", chargeable.Prefix);
        assertEquals(0, new BigDecimal("60").compareTo(chargeable.Quantity));
        assertEquals(0, new BigDecimal("1.0").compareTo(chargeable.BilledAmount));
    }

    @Test
    void Half_minute_is_half_the_per_minute_rate() {
        var thisCdr = new cdr();
        thisCdr.InPartnerId = 5;
        thisCdr.TerminatingCalledNumber = "8801712345678"; thisCdr.OriginatingCalledNumber = "8801712345678";
        thisCdr.DurationSec = new BigDecimal("30");
        var chargeable = BasicCharge.Default().Compute(
                thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner(5, null, 3)));

        assertEquals(0, new BigDecimal("30").compareTo(chargeable.Quantity));
        assertEquals(0, new BigDecimal("0.5").compareTo(chargeable.BilledAmount));
    }

    @Test
    void No_charge_when_service_group_not_detected() {
        var thisCdr = new cdr();
        thisCdr.InPartnerId = 5;
        thisCdr.TerminatingCalledNumber = "8801712345678"; thisCdr.OriginatingCalledNumber = "8801712345678";
        thisCdr.DurationSec = new BigDecimal("60");
        var chargeable = BasicCharge.Default().Compute(
                thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner(5, null, 1)));
        assertNull(chargeable);
    }

    @Test
    void No_charge_when_no_rate_plan_assigned() {
        var thisCdr = new cdr();
        thisCdr.InPartnerId = 6;
        thisCdr.TerminatingCalledNumber = "8801712345678"; thisCdr.OriginatingCalledNumber = "8801712345678";
        thisCdr.DurationSec = new BigDecimal("60");
        // SG10 detected (retail) but no tuple for partner 6
        var chargeable = BasicCharge.Default().Compute(
                thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner(6, null, 3)));
        assertNull(chargeable);
    }

    @Test
    void No_charge_when_number_matches_no_rate_prefix() {
        var thisCdr = new cdr();
        thisCdr.InPartnerId = 5;
        thisCdr.TerminatingCalledNumber = "8809999999"; thisCdr.OriginatingCalledNumber = "8809999999";
        thisCdr.DurationSec = new BigDecimal("60");
        var chargeable = BasicCharge.Default().Compute(
                thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner(5, null, 3)));
        assertNull(chargeable);
    }
}
