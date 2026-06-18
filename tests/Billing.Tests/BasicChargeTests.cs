using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using MediationModel;

namespace Billing.Tests;

/// <summary>The customer-leg charge end to end over the legacy entities: detect SG → resolve the tuple →
/// PrefixMatcher → the SG's service family → acc_chargeable. Proves the whole rating chain wires together
/// over a MediationContext.</summary>
public class BasicChargeTests
{
    // SG10 customer tuple for partner 5: per-minute 1.0 for prefix 1712, rate plan 7.
    private static MediationContext Mediation() => new()
    {
        RatePlanResolver = RatePlanResolver.Build(new[]
        {
            TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0,
                TestData.Ra(prefix: 1712, amount: 1.0m, idRatePlan: 7)),
        }),
    };

    private static IReadOnlyDictionary<int, Partner> Partners(params Partner[] ps) => ps.ToDictionary(p => p.IdPartner);

    [Fact]
    public void Charges_a_detected_call_end_to_end()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8801712345678", DurationSec = 60m };
        var chargeable = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 3 }));

        Assert.NotNull(chargeable);
        Assert.Equal(10, chargeable.servicegroup);     // retail → SG10
        Assert.Equal(10, chargeable.servicefamily);    // SF10
        Assert.Equal("1712", chargeable.Prefix);
        Assert.Equal(60m, chargeable.Quantity);
        Assert.Equal(1.0m, chargeable.BilledAmount);
    }

    [Fact]
    public void Half_minute_is_half_the_per_minute_rate()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8801712345678", DurationSec = 30m };
        var chargeable = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 3 }));

        Assert.Equal(30m, chargeable!.Quantity);
        Assert.Equal(0.5m, chargeable.BilledAmount);
    }

    [Fact]
    public void No_charge_when_service_group_not_detected()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8801712345678", DurationSec = 60m };
        var chargeable = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 1 }));
        Assert.Null(chargeable);
    }

    [Fact]
    public void No_charge_when_no_rate_plan_assigned()
    {
        var thisCdr = new cdr { InPartnerId = 6, TerminatingCalledNumber = "8801712345678", DurationSec = 60m };
        // SG10 detected (retail) but no tuple for partner 6
        var chargeable = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 6, PartnerType = 3 }));
        Assert.Null(chargeable);
    }

    [Fact]
    public void No_charge_when_number_matches_no_rate_prefix()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8809999999", DurationSec = 60m };
        var chargeable = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 3 }));
        Assert.Null(chargeable);
    }
}
