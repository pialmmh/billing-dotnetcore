using Billing.Mediation.Model;
using Billing.Mediation.Rating;

namespace Billing.Tests;

/// <summary>Rate-plan tuple resolution: route scope beats partner scope, lowest priority wins within a
/// key, and an unmatched call resolves to null (legacy A2ZRater.GetAssignmentTuples order).</summary>
public class RatePlanResolverTests
{
    private const int Customer = (int)AssignmentDirection.Customer;

    [Fact]
    public void Resolves_partner_scope()
    {
        var r = RatePlanResolver.Build(new[]
        {
            new RatePlanAssignmentTuple { Id = 1, IdService = 10, AssignDirection = Customer, IdPartner = 5, IdRatePlan = 7 },
        });
        Assert.Equal(7, r.Resolve(10, Customer, idPartner: 5, route: null)!.IdRatePlan);
    }

    [Fact]
    public void Route_scope_is_preferred_over_partner_scope()
    {
        var r = RatePlanResolver.Build(new[]
        {
            new RatePlanAssignmentTuple { Id = 1, IdService = 10, AssignDirection = Customer, IdPartner = 5, IdRatePlan = 7 },
            new RatePlanAssignmentTuple { Id = 2, IdService = 10, AssignDirection = Customer, Route = 99, IdRatePlan = 8 },
        });
        // Both a route (99) and a partner (5) match — the route tuple wins.
        Assert.Equal(8, r.Resolve(10, Customer, idPartner: 5, route: 99)!.IdRatePlan);
        // No route on the call → fall back to the partner tuple.
        Assert.Equal(7, r.Resolve(10, Customer, idPartner: 5, route: null)!.IdRatePlan);
    }

    [Fact]
    public void Lowest_priority_wins_within_a_key()
    {
        var r = RatePlanResolver.Build(new[]
        {
            new RatePlanAssignmentTuple { Id = 1, IdService = 11, AssignDirection = Customer, IdPartner = 5, Priority = 5, IdRatePlan = 70 },
            new RatePlanAssignmentTuple { Id = 2, IdService = 11, AssignDirection = Customer, IdPartner = 5, Priority = 1, IdRatePlan = 71 },
        });
        Assert.Equal(71, r.Resolve(11, Customer, idPartner: 5, route: null)!.IdRatePlan);
    }

    [Fact]
    public void Miss_returns_null()
    {
        var r = RatePlanResolver.Build(new[]
        {
            new RatePlanAssignmentTuple { Id = 1, IdService = 10, AssignDirection = Customer, IdPartner = 5, IdRatePlan = 7 },
        });
        Assert.Null(r.Resolve(10, Customer, idPartner: 404, route: null));   // unknown partner
        Assert.Null(r.Resolve(99, Customer, idPartner: 5, route: null));     // unknown service group
        Assert.Null(r.Resolve(10, (int)AssignmentDirection.Supplier, idPartner: 5, route: null)); // wrong direction
        Assert.Null(RatePlanResolver.Empty.Resolve(10, Customer, 5, null));
    }
}
