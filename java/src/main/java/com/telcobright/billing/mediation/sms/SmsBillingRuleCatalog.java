package com.telcobright.billing.mediation.sms;

import java.util.Map;

/**
 * The tenant's billing-rule wiring, exactly as legacy reads it (not served by config-manager, so it is loaded
 * from the tenant schema inside the batch transaction):
 * <pre>
 *   rateplanassignmenttuple.id
 *     → billingruleassignment(idRatePlanAssignmentTuple → idBillingRule, idServiceGroup)
 *     → jsonbillingrule(id).JsonExpression → BillingRule.IsPrepaid
 * </pre>
 * Legacy uses {@code billingruleassignment.idServiceGroup} to decide which tuples a service group may rate
 * against at all ({@code MediationContext}: {@code GroupBy(c => c.billingruleassignment.idServiceGroup)} →
 * {@code ServiceGroupWiseTupDefs}); {@link #IsAssignedTo} reproduces that gate.
 */
public record SmsBillingRuleCatalog(
        Map<Integer, Assignment> AssignmentsByTuple,
        Map<Integer, BillingRule> RulesById) {

    /** {@code billingruleassignment} row. */
    public record Assignment(int IdRatePlanAssignmentTuple, int IdBillingRule, int IdServiceGroup) {}

    /** The legacy {@code BillingRule} facts accounting needs (deserialized from {@code jsonbillingrule}). */
    public record BillingRule(int Id, String RuleName, boolean IsPrepaid) {}

    public static SmsBillingRuleCatalog Empty() {
        return new SmsBillingRuleCatalog(Map.of(), Map.of());
    }

    /** True when the tuple's billing-rule assignment belongs to {@code serviceGroupId} (the legacy SG gate). */
    public boolean IsAssignedTo(int idRatePlanAssignmentTuple, int serviceGroupId) {
        Assignment a = AssignmentsByTuple.get(idRatePlanAssignmentTuple);
        return a != null && a.IdServiceGroup() == serviceGroupId;
    }

    public Assignment AssignmentFor(int idRatePlanAssignmentTuple) {
        return AssignmentsByTuple.get(idRatePlanAssignmentTuple);
    }

    public BillingRule Rule(int idBillingRule) {
        return RulesById.get(idBillingRule);
    }
}
