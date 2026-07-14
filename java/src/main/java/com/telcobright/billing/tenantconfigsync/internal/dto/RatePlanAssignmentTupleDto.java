package com.telcobright.billing.tenantconfigsync.internal.dto;

/**
 * The rate-plan-assignment tuple as config-manager actually serves it — NESTED inside each rate-assign row
 * ({@code rateAssignsCustomer[i].ratePlanAssignmentTuple} / {@code rateAssignsSupplier[i]…}), NOT as a flat
 * {@code mediationContext.ratePlanAssignmentTuples} list. It identifies which
 * {@code (idService, assignDirection, idPartner|route, priority)} an assignment belongs to — the grouping key
 * {@code ConfigManagerMapper} uses to reconstruct the flat {@code rateplanassignmenttuple} list the
 * {@code RatePlanResolver}/{@code RateCache} are built from.
 *
 * <p>Field names match the served camelCase JSON (the config-manager client's mapper is case-insensitive).
 */
public final class RatePlanAssignmentTupleDto {
    public int id;
    public int idService;
    public int assignDirection;
    public Integer idPartner;
    public Integer route;
    public int priority;
}
