using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using Billing.Mediation.Rating.Internal;

namespace Billing.Tests;

/// <summary>The multi-tier traversal + contract: a 3-tier chain yields a 3-entry map keyed by dbName,
/// each tier carrying candidates; an empty chain (unknown tenant) rejects. Uses the stub rater.</summary>
public class MaxRateEngineTests
{
    private static readonly CallFacts Facts =
        new("res_205", PartnerId: 101, "8801000", "8801999", "10.0.0.1", ServiceType.Voice, StartEpochMillis: 0);

    private static MaxRateEngine NewEngine() => new(new StubTierRater());

    [Fact]
    public void Chain_yields_one_tier_per_db_name_with_candidates()
    {
        var med = MediationContext.Empty;
        var chain = new List<TierInput>
        {
            new("res_205", 101, med, new List<PackageAccount> { new() { Id = 9, IdPartner = 101, Uom = "TF_min" } }),
            new("res_203", 0, med, []),
            new("telcobright", 0, med, []),
        };

        var result = NewEngine().Resolve(Facts, chain);

        Assert.True(result.Ok);
        Assert.Equal(3, result.Tiers.Count);
        Assert.Contains("res_205", result.Tiers.Keys);
        Assert.Contains("res_203", result.Tiers.Keys);
        Assert.Contains("telcobright", result.Tiers.Keys);
        // leaf with a package → that package becomes the candidate
        var leaf = result.Tiers["res_205"];
        Assert.Equal(101, leaf.PartnerId);
        Assert.Single(leaf.Candidates);
        Assert.Equal(9, leaf.Candidates[0].PackageAccountId);
        Assert.Equal("TF_min", leaf.Candidates[0].Uom);
        // tier with no packages → one cash placeholder
        Assert.Equal("BDT", result.Tiers["telcobright"].Candidates.Single().Uom);
    }

    [Fact]
    public void Empty_chain_rejects()
    {
        var result = NewEngine().Resolve(Facts, []);

        Assert.False(result.Ok);
        Assert.Empty(result.Tiers);
        Assert.Contains("unknown tenant", result.RejectReason);
    }
}
