using Billing.Mediation.Model;
using Billing.Mediation.Rating;

namespace Billing.Tests;

/// <summary>Rate-plan tuple resolution over the verbatim legacy tuples: route scope beats partner scope,
/// the returned tuples are priority-ordered, and an unmatched call resolves to empty.</summary>
public class RatePlanResolverTests
{
    private const int Customer = (int)AssignmentDirection.Customer;

    [Fact]
    public void Resolves_partner_scope()
    {
        var r = RatePlanResolver.Build(new[] { TestData.Tup(10, Customer, 5, null, 0, TestData.Ra(1, 1.0m)) });
        var tuples = r.Resolve(10, Customer, idPartner: 5, route: null);
        Assert.Single(tuples);
        Assert.Equal(5, tuples[0].idpartner);
    }

    [Fact]
    public void Route_scope_is_preferred_over_partner_scope()
    {
        var r = RatePlanResolver.Build(new[]
        {
            TestData.Tup(10, Customer, 5, null, 0, TestData.Ra(1, 1.0m)),
            TestData.Tup(10, Customer, null, 99, 0, TestData.Ra(1, 2.0m)),
        });
        Assert.Equal(99, r.Resolve(10, Customer, 5, 99)[0].route);       // route wins
        Assert.Equal(5, r.Resolve(10, Customer, 5, null)[0].idpartner);  // fall back to partner
    }

    [Fact]
    public void Returned_tuples_are_priority_ordered()
    {
        var r = RatePlanResolver.Build(new[]
        {
            TestData.Tup(11, Customer, 5, null, 5, TestData.Ra(1, 1.0m)),
            TestData.Tup(11, Customer, 5, null, 1, TestData.Ra(1, 2.0m)),
        });
        Assert.Equal(new[] { 1, 5 }, r.Resolve(11, Customer, 5, null).Select(t => t.priority).ToArray());
    }

    [Fact]
    public void Miss_returns_empty()
    {
        var r = RatePlanResolver.Build(new[] { TestData.Tup(10, Customer, 5, null, 0, TestData.Ra(1, 1.0m)) });
        Assert.Empty(r.Resolve(10, Customer, idPartner: 404, route: null));              // unknown partner
        Assert.Empty(r.Resolve(99, Customer, idPartner: 5, route: null));                // unknown service group
        Assert.Empty(r.Resolve(10, (int)AssignmentDirection.Supplier, 5, null));         // wrong direction
        Assert.Empty(RatePlanResolver.Empty.Resolve(10, Customer, 5, null));
    }
}
