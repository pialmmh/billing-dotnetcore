using Billing.Mediation.Model;
using Billing.Mediation.Rating;

namespace Billing.Tests;

/// <summary>Golden checks for the basic A2Z charge math (pulse + min-duration threshold + surcharge),
/// ported from the legacy PrefixMatcher.</summary>
public class A2ZChargerTests
{
    // per-minute, no pulse, no surcharge
    [Fact]
    public void PerMinute_no_pulse()
    {
        var rate = new Rate { RateAmount = 1.0m };               // Resolution=0, MinDuration=0, Surcharge=0
        Assert.Equal((60m, 1.0m), AsTuple(A2ZCharger.Compute(rate, 60m, 60)));
        Assert.Equal((30m, 0.5m), AsTuple(A2ZCharger.Compute(rate, 30m, 60)));  // 30s ceil, 30*(1/60)
        Assert.Equal((13m, decimal.Round(13m / 60m, 8)), AsTuple(A2ZCharger.Compute(rate, 12.07m, 60))); // ceil to 13
    }

    // 60/60 pulse (round billed duration up to the next minute)
    [Fact]
    public void Per_minute_pulse_rounds_up()
    {
        var rate = new Rate { RateAmount = 1.0m, Resolution = 60 };
        Assert.Equal((60m, 1.0m), AsTuple(A2ZCharger.Compute(rate, 60m, 60)));
        Assert.Equal((120m, 2.0m), AsTuple(A2ZCharger.Compute(rate, 61m, 60)));   // 61 -> 120
        Assert.Equal((120m, 2.0m), AsTuple(A2ZCharger.Compute(rate, 119.9m, 60)));
    }

    // 30/6 with a flat initial-period charge; a short call only pays the setup
    [Fact]
    public void Initial_period_surcharge()
    {
        var rate = new Rate { RateAmount = 1.0m, Resolution = 6, SurchargeTime = 30, SurchargeAmount = 0.5m };
        // 12.07s -> ceil 13 -> pulse 6 -> 18; 18 < 30 => all surcharge, amount = 0.5
        Assert.Equal((18m, 0.5m), AsTuple(A2ZCharger.Compute(rate, 12.07m, 60)));
        // 42s -> ceil 42 -> pulse 6 -> 42; first 30 = 0.5 flat, remaining 12 * (1/60) = 0.2 => 0.7
        Assert.Equal((42m, 0.7m), AsTuple(A2ZCharger.Compute(rate, 42m, 60)));
    }

    // MinDurationSec as a millisecond rounding threshold
    [Fact]
    public void Min_duration_ms_threshold()
    {
        var rate = new Rate { RateAmount = 1.0m, MinDurationSec = 0.1m };
        Assert.Equal(60m, A2ZCharger.A2ZDuration(60.05m, rate));   // frac .05 < .1 -> floor
        Assert.Equal(61m, A2ZCharger.A2ZDuration(60.15m, rate));   // frac .15 >= .1 -> ceil
    }

    private static (decimal, decimal) AsTuple(A2ZChargeResult r) => (r.BilledDurationSec, r.Amount);
}
