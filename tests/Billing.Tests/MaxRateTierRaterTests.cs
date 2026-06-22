using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using Billing.Mediation.Rating.Internal;
using MediationModel;

namespace Billing.Tests;

/// <summary>The real GetMaxRatePerMinute tier rater: detect SG10 + match the customer rate over the RateCache
/// → a CASH candidate carrying the per-minute rate; eligible packages → package candidates (1 unit/min); no
/// service group → reject.</summary>
public class MaxRateTierRaterTests
{
    private static MediationContext Sg10() => MediationContext.ForRating(new[]
    {
        TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0, TestData.Ra(1712, 2.5m, idRatePlan: 7)),
    });

    private static readonly IReadOnlyDictionary<int, Partner> Retail5 =
        new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };

    private static CallFacts Facts(string called = "8801712345678") =>
        new("telcobright", PartnerId: 5, "8801999000111", called, "10.0.0.1", ServiceType.Voice, StartEpochMillis: 0);

    private static ITierRater Rater() => new MaxRateTierRater(BasicCharge.Default());

    [Fact]
    public void Detected_call_returns_a_cash_candidate_with_the_per_minute_rate()
    {
        var tier = new TierInput("telcobright", 5, Sg10(), Array.Empty<PackageAccount>(), Retail5);
        var result = Rater().RateTier(Facts(), tier);

        Assert.Equal(10, result.ServiceGroupId);
        Assert.Empty(result.RejectReason);
        var cash = Assert.Single(result.Candidates);
        Assert.Equal("BDT", cash.Uom);
        Assert.Equal(2.5d, cash.RatePerMinute);
        Assert.Equal(2.5d, cash.MaxAmountFirstMinute);   // 60s @ 2.5/min
    }

    [Fact]
    public void Eligible_packages_become_candidates_alongside_cash()
    {
        var packages = new List<PackageAccount> { new() { Id = 9, IdPartner = 5, Uom = "TF_min" } };
        var tier = new TierInput("telcobright", 5, Sg10(), packages, Retail5);
        var result = Rater().RateTier(Facts(), tier);

        Assert.Equal(2, result.Candidates.Count);   // package + cash
        Assert.Contains(result.Candidates, c => c.PackageAccountId == 9 && c.Uom == "TF_min" && c.MaxAmountFirstMinute == 1d);
        Assert.Contains(result.Candidates, c => c.Uom == "BDT" && c.RatePerMinute == 2.5d);
    }

    [Fact]
    public void No_service_group_rejects()
    {
        // partner type 1 (not retail) → SG10 not detected
        var partners = new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 1 } };
        var tier = new TierInput("telcobright", 5, Sg10(), Array.Empty<PackageAccount>(), partners);
        var result = Rater().RateTier(Facts(), tier);

        Assert.Equal(0, result.ServiceGroupId);
        Assert.Contains("service group", result.RejectReason);
        Assert.Empty(result.Candidates);
    }
}
