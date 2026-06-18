using Billing.Mediation.Summary;
using MediationModel;

namespace Billing.Tests;

/// <summary>Summary rollup: like calls (same tuple key) collapse to one summed row; a call differing in
/// any identity field (here the day bucket) rolls into its own row.</summary>
public class SummaryCacheTests
{
    private static cdr CallAt(DateTime start) => new()
    {
        SwitchId = 1, InPartnerId = 5, IncomingRoute = "in", OutgoingRoute = "out",
        OriginatingIP = "1.1.1.1", TerminatingIP = "2.2.2.2",
        StartTime = start, ChargingStatus = 1, DurationSec = 60m, RoundedDuration = 60m, Duration1 = 60m,
        CountryCode = "880", AnsIdTerm = 42, MatchedPrefixSupplier = "1712",
    };

    private static acc_chargeable Charge() => new()
    {
        servicegroup = 10, servicefamily = 10,
        BilledAmount = 1.0m, TaxAmount1 = 0.5m, Prefix = "1712", unitPriceOrCharge = 1.0m, idBilledUom = "BDT",
    };

    [Fact]
    public void Like_calls_merge_into_one_row()
    {
        var cache = new SummaryCache();
        cache.Add(CdrSummaryBuilder.Build(CallAt(new DateTime(2026, 6, 19, 10, 0, 0)), Charge(), SummaryBucket.Day));
        cache.Add(CdrSummaryBuilder.Build(CallAt(new DateTime(2026, 6, 19, 15, 0, 0)), Charge(), SummaryBucket.Day));

        Assert.Equal(1, cache.Count);                       // same day bucket → one row
        var row = cache.Items.Single();
        Assert.Equal(2, row.totalcalls);
        Assert.Equal(2.0m, row.customercost);
        Assert.Equal(120m, row.actualduration);
    }

    [Fact]
    public void Different_day_rolls_into_its_own_row()
    {
        var cache = new SummaryCache();
        cache.Add(CdrSummaryBuilder.Build(CallAt(new DateTime(2026, 6, 19, 10, 0, 0)), Charge(), SummaryBucket.Day));
        cache.Add(CdrSummaryBuilder.Build(CallAt(new DateTime(2026, 6, 20, 10, 0, 0)), Charge(), SummaryBucket.Day));

        Assert.Equal(2, cache.Count);
    }
}
