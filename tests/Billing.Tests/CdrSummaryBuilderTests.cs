using Billing.Mediation.Summary;
using MediationModel;

namespace Billing.Tests;

/// <summary>Building the per-call sum_voice_* summary from the cdr + customer chargeable: the right table
/// per SG, the common identity/count/duration fields, the day bucket, the customer cost/tax, and that two
/// like calls share a tuple key and merge.</summary>
public class CdrSummaryBuilderTests
{
    private static cdr Sg10Cdr() => new()
    {
        SwitchId = 1, InPartnerId = 5, OutPartnerId = 0,
        IncomingRoute = "in", OutgoingRoute = "out",
        OriginatingIP = "1.1.1.1", TerminatingIP = "2.2.2.2",
        StartTime = new DateTime(2026, 6, 19, 14, 30, 0),
        ConnectTime = new DateTime(2026, 6, 19, 14, 30, 1),
        ChargingStatus = 1, DurationSec = 60m, RoundedDuration = 60m, Duration1 = 60m,
        CountryCode = "880", AnsIdTerm = 42, MatchedPrefixSupplier = "1712",
    };

    private static acc_chargeable Sg10Chargeable() => new()
    {
        servicegroup = 10, servicefamily = 10,
        BilledAmount = 1.0m, TaxAmount1 = 0.5m, Prefix = "1712", unitPriceOrCharge = 1.0m, idBilledUom = "BDT",
    };

    [Fact]
    public void Sg10_builds_day_03_with_customer_cost_and_tax()
    {
        var summary = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Day);

        Assert.IsType<sum_voice_day_03>(summary);
        Assert.Equal(new DateTime(2026, 6, 19), summary.tup_starttime);   // day bucket
        Assert.Equal(1, summary.totalcalls);
        Assert.Equal(1, summary.successfulcalls);
        Assert.Equal(60m, summary.actualduration);
        Assert.Equal(1.0m, summary.customercost);
        Assert.Equal(0.5m, summary.tax1);
        Assert.Equal("1712", summary.tup_matchedprefixcustomer);
        Assert.Equal("880", summary.tup_countryorareacode);
        Assert.Equal("42", summary.tup_destinationId);
    }

    [Fact]
    public void Sg11_builds_day_02()
    {
        var cdr = Sg10Cdr();
        cdr.MatchedPrefixY = "1712";
        cdr.AnsIdOrig = 7;
        var chargeable = Sg10Chargeable();
        chargeable.servicegroup = 11;
        chargeable.servicefamily = 11;

        var summary = CdrSummaryBuilder.Build(cdr, chargeable, SummaryBucket.Day);

        Assert.IsType<sum_voice_day_02>(summary);
        Assert.Equal(1.0m, summary.customercost);
        Assert.Equal("7", summary.tup_sourceId);
        Assert.Equal("1712", summary.tup_matchedprefixcustomer);
    }

    [Fact]
    public void Hour_bucket_rounds_down_to_the_hour()
    {
        var summary = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Hour);
        Assert.IsType<sum_voice_hr_03>(summary);
        Assert.Equal(new DateTime(2026, 6, 19, 14, 0, 0), summary.tup_starttime);
    }

    [Fact]
    public void Like_calls_share_a_tuple_key_and_merge()
    {
        var a = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Day);
        var b = CdrSummaryBuilder.Build(Sg10Cdr(), Sg10Chargeable(), SummaryBucket.Day);

        Assert.Equal(a.GetTupleKey(), b.GetTupleKey());   // same identity → same rollup bucket
        a.Merge(b);
        Assert.Equal(2, a.totalcalls);
        Assert.Equal(2.0m, a.customercost);
        Assert.Equal(1.0m, a.tax1);
    }
}
