using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using MediationModel;

namespace Billing.Tests;

/// <summary>The basic charge end to end: detect SG → resolve the rate-plan tuple → longest-prefix the
/// normalized number in the today RateCache → A2Z duration+amount. Proves the whole rating chain wires
/// together over a MediationContext.</summary>
public class BasicChargeTests
{
    // Plan 7 holds a per-minute rate for prefix 1712 (national-significant form of 8801712...).
    private static MediationContext Mediation() => new()
    {
        RateCache = RateCache.Build(new Dictionary<int, IReadOnlyDictionary<string, Rate>>
        {
            [7] = new Dictionary<string, Rate>
            {
                ["1712"] = new() { Prefix = "1712", RateAmount = 1.0m, IdRatePlan = 7 },
            },
        }, new DateOnly(2026, 6, 19)),
        RatePlanResolver = RatePlanResolver.Build(new[]
        {
            new RatePlanAssignmentTuple
            {
                Id = 1, IdService = 10, AssignDirection = (int)AssignmentDirection.Customer,
                IdPartner = 5, IdRatePlan = 7,
            },
        }),
    };

    private static IReadOnlyDictionary<int, Partner> Partners(params Partner[] ps) => ps.ToDictionary(p => p.IdPartner);

    [Fact]
    public void Charges_a_detected_call_end_to_end()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8801712345678", DurationSec = 60m };
        var result = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 3 }));

        Assert.NotNull(result);
        Assert.Equal(10, result.Value.ServiceGroupId);     // retail → SG10
        Assert.Equal(7, result.Value.IdRatePlan);
        Assert.Equal("1712", result.Value.MatchedPrefix);
        Assert.Equal(60m, result.Value.BilledDurationSec);
        Assert.Equal(1.0m, result.Value.Amount);           // 60s per-minute @ 1.0
    }

    [Fact]
    public void Half_minute_is_half_the_per_minute_rate()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8801712345678", DurationSec = 30m };
        var result = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 3 }));

        Assert.Equal(30m, result!.Value.BilledDurationSec);
        Assert.Equal(0.5m, result.Value.Amount);
    }

    [Fact]
    public void No_charge_when_service_group_not_detected()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8801712345678", DurationSec = 60m };
        // partnerType 1 → neither SG10 nor SG11 claims
        var result = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 1 }));
        Assert.Null(result);
    }

    [Fact]
    public void No_charge_when_no_rate_plan_assigned()
    {
        var thisCdr = new cdr { InPartnerId = 6, TerminatingCalledNumber = "8801712345678", DurationSec = 60m };
        // SG10 detected (retail) but no tuple for partner 6 → no plan
        var result = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 6, PartnerType = 3 }));
        Assert.Null(result);
    }

    [Fact]
    public void No_charge_when_number_matches_no_rate_prefix()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8809999999", DurationSec = 60m };
        // resolves plan 7, but 9999999 has no prefix in the plan
        var result = BasicCharge.Default().Compute(
            thisCdr, AssignmentDirection.Customer, Mediation(), Partners(new Partner { IdPartner = 5, PartnerType = 3 }));
        Assert.Null(result);
    }
}
