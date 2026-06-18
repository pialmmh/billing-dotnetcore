using Billing.Mediation.Rating;

namespace Billing.Tests;

/// <summary>Faithful A2Z math (ported from ExecuteA2ZRating + PrefixMatcher.GetA2ZDuration): pulse, the
/// ms-threshold, and the SURCHARGE = minimum-initial-period model (NOT a flat fee).</summary>
public class A2ZRaterTests
{
    private static (decimal, decimal) T(A2ZRateResult r) => (r.BilledDurationSec, r.Amount);

    [Fact]
    public void Per_minute_no_pulse()
    {
        var rate = TestData.Ra(prefix: 1, amount: 1.0m);   // Resolution 0, SurchargeTime 0
        Assert.Equal((60m, 1.0m), T(A2ZRater.Rate(rate, 60m)));
        Assert.Equal((30m, 0.5m), T(A2ZRater.Rate(rate, 30m)));
        Assert.Equal((13m, decimal.Round(13m / 60m, 8)), T(A2ZRater.Rate(rate, 12.07m)));  // ceil to 13
    }

    [Fact]
    public void Per_minute_pulse_rounds_up()
    {
        var rate = TestData.Ra(1, 1.0m, resolution: 60);
        Assert.Equal((60m, 1.0m), T(A2ZRater.Rate(rate, 60m)));
        Assert.Equal((120m, 2.0m), T(A2ZRater.Rate(rate, 61m)));     // 61 -> next minute
    }

    [Fact]
    public void Surcharge_is_a_minimum_initial_period_not_a_flat_fee()
    {
        var rate = TestData.Ra(1, 1.0m, resolution: 6, surchargeTime: 30);
        // 12.07s <= 30 => the whole minimum initial period bills: 30s, amount 30/60 = 0.5
        Assert.Equal((30m, 0.5m), T(A2ZRater.Rate(rate, 12.07m)));
        // 42s > 30 => 30 (initial) + pulse6(12)=12 => 42; amount 42/60 = 0.7
        Assert.Equal((42m, 0.7m), T(A2ZRater.Rate(rate, 42m)));
    }

    [Fact]
    public void Min_duration_ms_threshold()
    {
        var rate = TestData.Ra(1, 1.0m, minDurationSec: 0.1f);
        Assert.Equal(60m, A2ZRater.A2ZDuration(60.05m, rate));   // frac .05 < .1 -> floor
        Assert.Equal(61m, A2ZRater.A2ZDuration(60.15m, rate));   // frac .15 >= .1 -> ceil
    }
}
