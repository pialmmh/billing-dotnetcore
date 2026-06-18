using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;

namespace Billing.Tests;

/// <summary>The finalize compute core: per-tier settlement over the call's chain (detect → resolve →
/// RateCache → A2Z), cash vs package uom split, multi-tier totals, unrated tiers, and the unanswered /
/// empty-chain edges. Mirrors the MaxRateEngine shape (pure engine, chain handed in).</summary>
public class FinalizeEngineTests
{
    // Plan 7: per-minute 1.0 for prefix 1712; tuple assigns it to SG10 customer for partner 5.
    private static MediationContext Mediation() => new()
    {
        RateCache = RateCache.Build(new Dictionary<int, IReadOnlyDictionary<string, Rate>>
        {
            [7] = new Dictionary<string, Rate> { ["1712"] = new() { Prefix = "1712", RateAmount = 1.0m, IdRatePlan = 7 } },
        }, new DateOnly(2026, 6, 19)),
        RatePlanResolver = RatePlanResolver.Build(new[]
        {
            new RatePlanAssignmentTuple { Id = 1, IdService = 10, AssignDirection = (int)AssignmentDirection.Customer, IdPartner = 5, IdRatePlan = 7 },
        }),
    };

    private static readonly IReadOnlyDictionary<int, Partner> RetailPartner5 =
        new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };

    private static FinalizeFacts Facts(string called = "8801712345678", int billsec = 60, bool answered = true) =>
        new("telcobright", "8801999000111", called, ServiceType.Voice, SwitchId: 1, "in", "out", billsec, answered, "uid-1");

    private static FinalizeTierInput Tier(string dbName, TierMode mode, TierReserved? reserved) =>
        new(dbName, PartnerId: 5, Mediation(), RetailPartner5, mode, reserved);

    [Fact]
    public void Cash_tier_settles_to_the_a2z_amount()
    {
        var result = FinalizeEngine.Default().Finalize(Facts(),
            new[] { Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", 2.0m)) });

        Assert.True(result.Success);
        var s = result.Settlements["telcobright"];
        Assert.Equal(10, s.ServiceGroupId);
        Assert.Equal(1.0m, s.Charged);
        Assert.Equal(1.0m, s.InPartnerCost);
        Assert.Equal(0m, s.PackageAmount);
        Assert.Equal("1712", s.MatchedPrefix);
        Assert.Equal(1.0m, result.TotalCharged);
    }

    [Fact]
    public void Package_tier_settles_to_consumed_minutes()
    {
        var result = FinalizeEngine.Default().Finalize(Facts(),
            new[] { Tier("res_233", TierMode.CustomerOnly, new TierReserved(200, "TF_min", 5.0m)) });

        var s = result.Settlements["res_233"];
        Assert.Equal("TF_min", s.Uom);
        Assert.Equal(1.0m, s.PackageAmount);     // 60s → 1 minute
        Assert.Equal(1.0m, s.Charged);
        Assert.Equal(0m, s.InPartnerCost);
    }

    [Fact]
    public void Multi_tier_settles_each_and_sums_the_total()
    {
        var result = FinalizeEngine.Default().Finalize(Facts(),
            new[]
            {
                Tier("res_233", TierMode.CustomerOnly, new TierReserved(200, "BDT", 2.0m)),
                Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", 2.0m)),
            });

        Assert.True(result.Success);
        Assert.Equal(2, result.Settlements.Count);
        Assert.Equal(1.0m, result.Settlements["res_233"].Charged);
        Assert.Equal(1.0m, result.Settlements["telcobright"].Charged);
        Assert.Equal(2.0m, result.TotalCharged);
    }

    [Fact]
    public void Unrated_tier_fails_the_call()
    {
        // 8809999999 normalizes to 9999999 — no prefix in plan 7.
        var result = FinalizeEngine.Default().Finalize(Facts(called: "8809999999"),
            new[] { Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", 2.0m)) });

        Assert.False(result.Success);
        Assert.NotNull(result.Settlements["telcobright"].Error);
        Assert.Equal(0m, result.TotalCharged);
    }

    [Fact]
    public void Empty_chain_is_an_unknown_tenant()
    {
        var result = FinalizeEngine.Default().Finalize(Facts(), Array.Empty<FinalizeTierInput>());
        Assert.False(result.Success);
        Assert.Contains("unknown tenant", result.Error);
        Assert.Empty(result.Settlements);
    }

    [Fact]
    public void Unanswered_call_settles_to_zero_without_error()
    {
        var result = FinalizeEngine.Default().Finalize(Facts(billsec: 0, answered: false),
            new[] { Tier("telcobright", TierMode.Full, new TierReserved(100, "BDT", 0m)) });

        Assert.True(result.Success);
        Assert.Equal(0m, result.Settlements["telcobright"].Charged);
        Assert.Equal(0m, result.TotalCharged);
    }
}
