package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.util.List;

/**
 * One mediated cdr: the call plus every chargeable its service group's configured rules produced
 * (customer + supplier legs). {@code Customer} is the customer-leg chargeable the summary reads.
 */
public record RatedCdr(cdr Cdr, List<acc_chargeable> Chargeables) {
    public acc_chargeable Customer() {
        return Chargeables.stream()
                .filter(c -> c.assignedDirection != null
                        && c.assignedDirection == (byte) AssignmentDirection.Customer.value)
                .findFirst()
                .or(() -> Chargeables.stream().findFirst())
                .orElse(null);
    }
}
