package com.telcobright.billing.tenantconfigsync.internal.dto;

import java.time.LocalDateTime;

/**
 * A served rate-assign row ({@code rateAssignsCustomer} / {@code rateAssignsSupplier}). config-manager serves
 * the rate-plan-assignment tuple NESTED here (see {@link RatePlanAssignmentTupleDto}); the mapper groups these
 * by {@code ratePlanAssignmentTuple.id} to rebuild the flat tuple list the RateCache JOIN needs. Only the
 * fields that JOIN uses are bound (the assign's idRatePlan + its date span); the rest of the served row is
 * ignored (unknown-properties are off in the config-manager client's mapper).
 *
 * <p><b>idRatePlan:</b> the {@code TupleRateLoader} reads the join {@code rateassign.Inactive} AS the
 * idRatePlan (a legacy quirk). config-manager fills the served {@code inactive} with that plan id, and also
 * serves the plan explicitly under {@code ratePlan} — {@link #idRatePlan()} prefers the explicit
 * {@code ratePlan.id} and falls back to {@code inactive} (they agree in the served data).
 */
public final class RateAssignDto {
    /** Legacy {@code rateassign.Inactive} = the idRatePlan of this assignment (not an "is-inactive" flag). */
    public int inactive;
    public LocalDateTime startDate;
    public LocalDateTime endDate;

    /** The tuple this assignment belongs to (grouping key). */
    public RatePlanAssignmentTupleDto ratePlanAssignmentTuple;

    /** The nested rate plan; {@code ratePlan.id} is the authoritative idRatePlan for the JOIN. */
    public RatePlanRef ratePlan;

    /** idRatePlan for the RateCache JOIN: the explicit nested plan id when served, else the legacy Inactive. */
    public int idRatePlan() {
        return ratePlan != null && ratePlan.id > 0 ? ratePlan.id : inactive;
    }

    /** Minimal projection of the nested {@code ratePlan} — only its id is needed for the JOIN. */
    public static final class RatePlanRef {
        public int id;
    }
}
