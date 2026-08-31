// Same package as the SUT (CustomerChargeGuard) per RULE T0.
package com.telcobright.billing.mediation.cdr;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.model.AssignmentDirection;

/**
 * The revenue-leg guard: a BILLABLE call keeps in cdr iff a CUSTOMER (revenue) rate matched — a matched rate
 * of amount 0 is a VALID zero-rated call. Only "no customer leg produced" is RATE_NOT_FOUND -> cdrerror.
 */
class CustomerChargeGuardTests {

    private static acc_chargeable leg(int dir, String rate) {
        acc_chargeable c = new acc_chargeable();
        c.assignedDirection = (byte) dir;
        c.unitPriceOrCharge = rate == null ? null : new BigDecimal(rate);
        c.servicegroup = 10;
        return c;
    }

    @Test
    void Customer_leg_with_positive_rate_counts() {
        assertTrue(CustomerChargeGuard.HasCustomerLeg(List.of(leg(AssignmentDirection.Customer.value, "0.36"))));
    }

    @Test
    void Customer_leg_with_ZERO_rate_still_counts_valid_zero_rated_call() {
        // a matched rate of 0 is a valid configured zero-rate — the revenue leg exists, so it stays in cdr.
        assertTrue(CustomerChargeGuard.HasCustomerLeg(List.of(leg(AssignmentDirection.Customer.value, "0"))));
    }

    @Test
    void No_chargeables_means_no_customer_leg() {
        assertFalse(CustomerChargeGuard.HasCustomerLeg(List.of()));
    }

    @Test
    void ICX_supplier_only_leg_is_not_a_customer_leg() {
        // the isolation fix: an SG10 call with only the ICX (supplier) cost leg has NO revenue leg -> false.
        assertFalse(CustomerChargeGuard.HasCustomerLeg(List.of(leg(AssignmentDirection.Supplier.value, "0.10"))));
        // and a customer leg alongside an ICX leg is still detected.
        assertTrue(CustomerChargeGuard.HasCustomerLeg(List.of(
                leg(AssignmentDirection.Supplier.value, "0.10"),
                leg(AssignmentDirection.Customer.value, "0.36"))));
    }
}
