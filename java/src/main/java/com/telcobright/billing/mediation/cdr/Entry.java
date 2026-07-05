package com.telcobright.billing.mediation.cdr;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;

import java.util.List;

/**
 * One outbox entry: the rated cdr + ALL its chargeable legs (customer + supplier). Each consumer picks
 * the leg(s) it needs — the voice summary reads the customer leg ({@code assignedDirection == 1}), the
 * chargeable summary rolls up every leg. (Blob v2: the old shape carried only the customer leg, which
 * silently dropped the SG10 supplier cost from every rollup.)
 */
public record Entry(cdr Cdr, List<acc_chargeable> Chargeables) {

    /** The customer-leg chargeable (what the voice summary reads) — not serialized into the blob. */
    @JsonIgnore
    public acc_chargeable Customer() {
        return Chargeables.stream()
                .filter(c -> c.assignedDirection != null
                        && c.assignedDirection == (byte) AssignmentDirection.Customer.value)
                .findFirst()
                .or(() -> Chargeables.stream().findFirst())
                .orElse(null);
    }
}
