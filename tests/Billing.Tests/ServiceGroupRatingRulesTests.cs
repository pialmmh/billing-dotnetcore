using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using MediationModel;

namespace Billing.Tests;

/// <summary>Service-group-wise CONFIGURED rating rules (legacy ExecuteRating): BasicCharge.Rate runs the
/// detected SG's configured RatingRules — each names a family (by id) + direction — over the RateCache. These
/// prove the family/direction set is CONFIG-DRIVEN, not hardcoded: a disabled SG charges nothing, a swapped
/// family id changes the family that runs, and two rules yield two chargeables (customer + supplier).</summary>
public class ServiceGroupRatingRulesTests
{
    private static readonly IReadOnlyDictionary<int, Partner> Retail5 =
        new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };

    private static cdr Sg10Cdr() => new()
    {
        InPartnerId = 5, OutPartnerId = 7, TerminatingCalledNumber = "8801712345678",
        DurationSec = 60m, StartTime = new DateTime(2026, 6, 19), AnswerTime = new DateTime(2026, 6, 19),
        ChargingStatus = 1, UniqueBillId = "uid-1",
    };

    private static rateplanassignmenttuple CustTuple() =>
        TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0, TestData.Ra(1712, 1.0m, idRatePlan: 7));
    private static rateplanassignmenttuple SuppTuple() =>
        TestData.Tup(10, (int)AssignmentDirection.Supplier, 7, null, 0, TestData.Ra(1712, 2.0m, idRatePlan: 8));

    private static IReadOnlyDictionary<int, ServiceGroupConfiguration> Sg10(bool disabled, params RatingRule[] rules) =>
        new Dictionary<int, ServiceGroupConfiguration>
        {
            [10] = new() { ServiceGroupId = 10, Disabled = disabled, RatingRules = rules },
        };

    [Fact]
    public void Default_config_runs_the_sg10_customer_rule()
    {
        var med = MediationContext.ForRating(new[] { CustTuple() });   // built-in default SG configs
        var chargeables = BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5);

        Assert.Single(chargeables);
        Assert.Equal(10, chargeables[0].servicefamily);   // SF10 customer (supplier rule finds no tuple)
    }

    [Fact]
    public void Two_configured_rules_yield_a_customer_and_a_supplier_leg()
    {
        var med = MediationContext.ForRating(new[] { CustTuple(), SuppTuple() });   // default: SF10 cust + SF1 supplier
        var chargeables = BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5);

        Assert.Equal(2, chargeables.Count);
        Assert.Contains(chargeables, c => c.servicefamily == 10 && c.assignedDirection == 1);   // customer
        Assert.Contains(chargeables, c => c.servicefamily == 1 && c.assignedDirection == 2);    // supplier
    }

    [Fact]
    public void Family_is_taken_from_config_not_hardcoded()
    {
        // configure SG10's customer rule to run SF11 instead of SF10
        var med = MediationContext.ForRating(new[] { CustTuple() },
            serviceGroupConfigurations: Sg10(disabled: false, new RatingRule { IdServiceFamily = 11, AssignDirection = 1 }));
        var chargeables = BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5);

        Assert.Single(chargeables);
        Assert.Equal(11, chargeables[0].servicefamily);   // SF11, because config said so
    }

    [Fact]
    public void Disabled_service_group_charges_nothing()
    {
        var med = MediationContext.ForRating(new[] { CustTuple() },
            serviceGroupConfigurations: Sg10(disabled: true, new RatingRule { IdServiceFamily = 10, AssignDirection = 1 }));

        Assert.Empty(BasicCharge.Default().Rate(Sg10Cdr(), med, Retail5));
        Assert.Null(BasicCharge.Default().Compute(Sg10Cdr(), AssignmentDirection.Customer, med, Retail5));
    }
}
