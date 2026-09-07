package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.util.List;

/**
 * The revenue-leg guard for a BILLABLE call (duration &gt; 0): did rating produce a CUSTOMER (revenue)
 * chargeable at all? The rate table is the source of truth for the amount — a prefix that matches its rate
 * plan at {@code rateamount = 0} is a VALID zero-rated call (a produced customer leg with amount 0), so the
 * guard never inspects the amount. It distinguishes only:
 *
 * <ul>
 * <li>a CUSTOMER leg was produced (a customer rate matched — any amount, including 0) → keep in {@code cdr};</li>
 * <li>NO customer leg was produced (no customer rate matched) → {@code RATE_NOT_FOUND} → {@code cdrerror}.</li>
 * </ul>
 *
 * <p>Keying on the customer ({@link AssignmentDirection#Customer}) leg — not on {@code chargeables.isEmpty()} —
 * also closes a real gap: an SG10 call can produce ONLY the ICX (supplier-direction) cost leg with no customer
 * leg; the non-empty list would previously slip a revenue-less call into {@code cdr}. That is now
 * {@code RATE_NOT_FOUND}.</p>
 *
 * <p>{@code duration == 0} failed calls never reach this guard — they stay in the normal {@code cdr} pipeline
 * for ASR. Nothing here recovers or reprocesses: a {@code cdrerror} call stays there, with its reason, until an
 * operator fixes the root cause and manually reprocesses.</p>
 */
public final class CustomerChargeGuard {
    private CustomerChargeGuard() {}

    /** True if the rated chargeables contain a CUSTOMER (revenue) leg — a customer rate matched (any amount). */
    public static boolean HasCustomerLeg(List<acc_chargeable> chargeables) {
        for (acc_chargeable c : chargeables)
            if (c.assignedDirection != null && c.assignedDirection == (byte) AssignmentDirection.Customer.value)
                return true;
        return false;
    }
}
