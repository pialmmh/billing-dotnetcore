using Billing.Mediation.Model;
using Billing.Mediation.Rating;

namespace Billing.Tests;

/// <summary>Today-only RateCache: longest-prefix match within a plan, miss returns null, and a day
/// rollover is detectable so the boundary refresher knows to rebuild.</summary>
public class RateCacheTests
{
    private static RateCache Build() => RateCache.Build(
        new Dictionary<int, IReadOnlyDictionary<string, Rate>>
        {
            [1] = new Dictionary<string, Rate>
            {
                ["880"]   = new() { Prefix = "880",   RateAmount = 1.0m, IdRatePlan = 1 },
                ["8801"]  = new() { Prefix = "8801",  RateAmount = 2.0m, IdRatePlan = 1 },
                ["88017"] = new() { Prefix = "88017", RateAmount = 3.0m, IdRatePlan = 1 },
            },
        },
        new DateOnly(2026, 6, 17));

    [Fact]
    public void Longest_prefix_wins()
    {
        var cache = Build();
        Assert.Equal(3.0m, cache.FindRate(1, "8801712345")!.RateAmount);  // 88017 (longest)
        Assert.Equal(2.0m, cache.FindRate(1, "8801812345")!.RateAmount);  // 8801, not 88017
        Assert.Equal(1.0m, cache.FindRate(1, "8809912345")!.RateAmount);  // 880
    }

    [Fact]
    public void Miss_returns_null()
    {
        var cache = Build();
        Assert.Null(cache.FindRate(1, "9990000"));    // no prefix matches
        Assert.Null(cache.FindRate(42, "8801712"));   // unknown plan
    }

    [Fact]
    public void Staleness_detects_day_rollover()
    {
        var cache = Build();   // built for 2026-06-17
        Assert.False(cache.IsStale(new DateOnly(2026, 6, 17)));
        Assert.True(cache.IsStale(new DateOnly(2026, 6, 18)));
    }
}
