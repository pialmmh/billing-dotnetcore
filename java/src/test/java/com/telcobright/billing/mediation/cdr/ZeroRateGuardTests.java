// Same package as the SUT (ZeroRateGuard) per RULE T0.
package com.telcobright.billing.mediation.cdr;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.model.AssignmentDirection;

/**
 * The zero-rate decision matrix — routing keyed on the rating OUTCOME of the CUSTOMER leg, never on the
 * amount alone. Legitimate free (internal/BDIX/configured-free) must stay in cdr; accidental zero must go to
 * cdrerror; a duration=0 failed call never reaches this guard (covered in {@link CdrPipelineTests}).
 */
class ZeroRateGuardTests {

    private static acc_chargeable leg(int dir, String rate, String prefix) {
        acc_chargeable c = new acc_chargeable();
        c.assignedDirection = (byte) dir;
        c.unitPriceOrCharge = rate == null ? null : new BigDecimal(rate);
        c.Prefix = prefix;
        c.servicegroup = 10;
        return c;
    }

    private static MediationContext med(String... freePrefixes) {
        MediationContext m = new MediationContext();
        for (String p : freePrefixes) m.FreePrefixes.add(p);
        return m;
    }

    @Test
    void Matched_rate_gt_0_is_RATE_FOUND_and_stays_in_cdr() {
        var r = ZeroRateGuard.classify(List.of(leg(AssignmentDirection.Customer.value, "0.36", "8801712")), med());
        assertEquals(ZeroRateGuard.Status.RATE_FOUND, r.status());
        assertFalse(r.status().toCdrError);
    }

    @Test
    void No_customer_leg_is_RATE_NOT_FOUND_and_goes_to_cdrerror() {
        // an empty list, and an ICX-supplier-only list (the latent SG10 bug: revenue leg missing) both -> error.
        assertEquals(ZeroRateGuard.Status.RATE_NOT_FOUND, ZeroRateGuard.classify(List.of(), med()).status());
        var supplierOnly = ZeroRateGuard.classify(
                List.of(leg(AssignmentDirection.Supplier.value, "0.10", "8801712")), med());
        assertEquals(ZeroRateGuard.Status.RATE_NOT_FOUND, supplierOnly.status());
        assertTrue(supplierOnly.status().toCdrError);
    }

    @Test
    void Zero_rate_with_declared_free_prefix_is_INTENTIONALLY_FREE_and_stays_in_cdr() {
        var r = ZeroRateGuard.classify(
                List.of(leg(AssignmentDirection.Customer.value, "0", "096")), med("096", "16", "111"));
        assertEquals(ZeroRateGuard.Status.INTENTIONALLY_FREE, r.status());
        assertFalse(r.status().toCdrError);
        assertEquals("096", r.customerPrefix());
    }

    @Test
    void Zero_rate_with_prefix_NOT_declared_free_is_UNEXPECTED_ZERO_RATE_and_goes_to_cdrerror() {
        var r = ZeroRateGuard.classify(
                List.of(leg(AssignmentDirection.Customer.value, "0", "8801712")), med("096", "16"));
        assertEquals(ZeroRateGuard.Status.UNEXPECTED_ZERO_RATE, r.status());
        assertTrue(r.status().toCdrError);
        assertEquals("8801712", r.customerPrefix());
    }

    @Test
    void Zero_rate_with_no_free_set_is_GUARD_INACTIVE_and_stays_in_cdr() {
        // safe fallback: until the tenant declares its free set, a matched-zero keeps pre-guard behavior (cdr).
        var r = ZeroRateGuard.classify(
                List.of(leg(AssignmentDirection.Customer.value, "0", "8801712")), med());
        assertEquals(ZeroRateGuard.Status.GUARD_INACTIVE_NO_FREE_SET, r.status());
        assertFalse(r.status().toCdrError);
    }

    @Test
    void Customer_leg_is_isolated_from_a_paid_ICX_supplier_leg() {
        // a zero customer rate must NOT be masked by a non-zero supplier(ICX) leg in the same list.
        var r = ZeroRateGuard.classify(List.of(
                leg(AssignmentDirection.Customer.value, "0", "8801712"),
                leg(AssignmentDirection.Supplier.value, "0.10", "8801712")), med("096"));
        assertEquals(ZeroRateGuard.Status.UNEXPECTED_ZERO_RATE, r.status());
    }
}
