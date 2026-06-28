namespace Billing.Mediation.Model;

/// <summary>The assignment direction of a rate plan (legacy ServiceAssignmentDirection): the customer
/// leg is what we charge the call's in-partner; the supplier leg is what we pay the out-partner. The
/// tuple itself is the verbatim legacy <c>MediationModel.rateplanassignmenttuple</c>.</summary>
public enum AssignmentDirection
{
    None = 0,
    Customer = 1,
    Supplier = 2,
}
