namespace Billing.Mediation.Model;

/// <summary>The assignment direction of a rate plan (legacy ServiceAssignmentDirection): the customer
/// leg is what we charge the call's in-partner; the supplier leg is what we pay the out-partner.</summary>
public enum AssignmentDirection
{
    None = 0,
    Customer = 1,
    Supplier = 2,
}

/// <summary>
/// One rate-plan-assignment tuple — the lean mirror of legacy <c>rateplanassignmenttuple</c>. It binds a
/// service group + direction to a rate plan, scoped either to a partner or to a route, with a priority
/// for tie-breaks. Resolution is route-first then partner (the A2ZRater order); the winning tuple's
/// <see cref="IdRatePlan"/> selects the plan in the today-only RateCache.
///
/// CONTRACT (config-manager): <see cref="IdRatePlan"/> is resolved per tuple (the legacy tuple reaches
/// its plan through its <c>rateassigns[].idrateplan</c>) and MUST equal a key of
/// <c>RatePlanWiseTodaysRates</c> so <c>RateCache.FindRate(IdRatePlan, number)</c> hits.
/// </summary>
public sealed record RatePlanAssignmentTuple
{
    public int Id { get; init; }

    /// <summary>The service group this assignment is for (e.g. 10 outgoing, 11 incoming).</summary>
    public int IdService { get; init; }

    /// <summary>1 = customer (charge-in), 2 = supplier (pay-out). See <see cref="AssignmentDirection"/>.</summary>
    public int AssignDirection { get; init; }

    /// <summary>Partner-scoped assignment (null/0 = not partner-scoped).</summary>
    public int? IdPartner { get; init; }

    /// <summary>Route-scoped assignment (null/0 = not route-scoped); matched before the partner scope.</summary>
    public int? Route { get; init; }

    /// <summary>Lower wins when several tuples share a key.</summary>
    public int Priority { get; init; }

    /// <summary>The assigned rate plan — the RateCache key for this tuple.</summary>
    public int IdRatePlan { get; init; }
}
