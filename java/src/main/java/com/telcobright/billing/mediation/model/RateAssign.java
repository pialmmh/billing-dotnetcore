// Ported from legacy Mediation/Model/Entities.cs (split per the one-type-per-file rule).
package com.telcobright.billing.mediation.model;

/** Assigns a rate plan to a partner in a direction (customer = charge-in, supplier = pay-out). */
public record RateAssign(
        int Id,
        int FromPartnerId,
        int ToPartnerId,
        int RatePlanId,
        String Direction
) {
}
