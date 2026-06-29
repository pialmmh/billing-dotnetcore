// Ported from legacy Mediation/Model/RatePlanAssignment.cs (the file declares the AssignmentDirection enum).
package com.telcobright.billing.mediation.model;

/**
 * The assignment direction of a rate plan (legacy ServiceAssignmentDirection): the customer leg is what we
 * charge the call's in-partner; the supplier leg is what we pay the out-partner. The tuple itself is the
 * verbatim legacy {@code MediationModel.rateplanassignmenttuple}.
 *
 * <p>RULE 6: the enum is cast to int/sbyte downstream (e.g. {@code (sbyte)AssignmentDirection.Customer} in
 * CdrPipeline, {@code (int)AssignmentDirection.Customer} in BasicCharge), so an explicit {@code value} field
 * replicates the exact C# numeric values. Compare downstream via {@code AssignmentDirection.Customer.value}.</p>
 */
public enum AssignmentDirection {
    None(0),
    Customer(1),
    Supplier(2);

    public final int value;

    AssignmentDirection(int value) {
        this.value = value;
    }
}
