using Billing.Mediation.Cdr;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Summary;
using MediationModel;
using MediationModel.enums;
using TelcobrightMediation;

namespace Billing.Tests;

/// <summary>The decoupled CDR processing pipeline over an already-fetched batch: mediate each cdr (detect SG
/// → rate via the RateCache) → build + merge summaries → one write. Calls that match no rate fall to the
/// unrated bucket; same-bucket calls merge onto one summary row.</summary>
public class CdrProcessorTests
{
    private sealed class InMemorySummaryStore : ISummaryStore
    {
        public List<string> ExecutedSql { get; } = new();
        public List<(CdrSummaryType Table, DateTime[] StartTimes)> Loads { get; } = new();
        public IReadOnlyList<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, IReadOnlyCollection<DateTime> startTimes)
        {
            Loads.Add((table, startTimes.ToArray()));
            return new List<AbstractCdrSummary>();
        }
        public int ExecuteNonQuery(string sql) { ExecutedSql.Add(sql); return 1; }
    }

    // SG10 customer rating config: per-minute 1.0 for prefix 1712 (partner 5).
    private static MediationContext Mediation() => MediationContext.ForRating(new[]
    {
        TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0, TestData.Ra(1712, 1.0m, idRatePlan: 7)),
    });

    private static readonly IReadOnlyDictionary<int, Partner> RetailPartner5 =
        new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };

    // A retail (SG10) call that is both rate-able (in-partner + called number) and summary-ready.
    private static cdr Call(string called, DateTime when) => new()
    {
        SwitchId = 1, InPartnerId = 5, IncomingRoute = "in", OutgoingRoute = "out",
        OriginatingIP = "1.1.1.1", TerminatingIP = "2.2.2.2",
        TerminatingCalledNumber = called, StartTime = when, AnswerTime = when, ChargingStatus = 1,
        DurationSec = 60m, RoundedDuration = 60m, Duration1 = 60m, CountryCode = "880",
        AnsIdTerm = 42, MatchedPrefixSupplier = "1712",
    };

    [Fact]
    public void Processes_a_batch_rates_each_and_writes_summaries()
    {
        var store = new InMemorySummaryStore();
        var when = new DateTime(2026, 6, 19, 14, 30, 0);
        var batch = new CdrBatch(Mediation(), RetailPartner5, new[]
        {
            Call("8801712345678", when),
            Call("8801712000000", when),   // same prefix 1712, same bucket → merges onto the same row
            Call("8809999999", when),      // normalizes to 9999999 → no rate prefix → unrated
        }, store);

        var result = CdrProcessor.Default().Process(batch);

        Assert.Equal(3, result.Total);
        Assert.Equal(2, result.Rated.Count);
        Assert.Single(result.Unrated);
        Assert.Equal(2.0m, result.TotalCharged);       // two 1.0 calls
        Assert.All(result.Rated, r => Assert.Equal(10, r.Customer!.servicegroup));

        // cdr rows: the 2 mediated cdrs go out as ONE batched insert.
        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into cdr (")));
        Assert.Equal(2, store.ExecutedSql.First(s => s.StartsWith("insert into cdr (")).Split("),(").Length);

        // chargeable rows: the 2 customer legs go out as ONE batched insert; each got a new id.
        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into acc_chargeable")));
        var chargeableInsert = store.ExecutedSql.First(s => s.StartsWith("insert into acc_chargeable"));
        Assert.Equal(2, chargeableInsert.Split("),(").Length);     // two value tuples
        Assert.All(result.Rated, r => Assert.True(r.Customer!.id > 0));

        // both rated calls fall in the SAME day+hr bucket → exactly one row written per table.
        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into sum_voice_day_03")));
        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into sum_voice_hr_03")));
    }

    [Fact]
    public void Prev_summary_loads_every_bucket_the_batch_touches()
    {
        var store = new InMemorySummaryStore();
        // batch spans TWO hours of the same day — the legacy DatesInvolved/HoursInvolved case.
        var batch = new CdrBatch(Mediation(), RetailPartner5, new[]
        {
            Call("8801712345678", new DateTime(2026, 6, 19, 14, 30, 0)),
            Call("8801712000000", new DateTime(2026, 6, 19, 15, 30, 0)),
        }, store);

        CdrProcessor.Default().Process(batch);

        // the hr table is loaded ONCE (not per-cdr), with BOTH involved hour buckets (legacy HoursInvolved).
        var hrLoad = store.Loads.Single(l => l.Table == CdrSummaryType.sum_voice_hr_03);
        Assert.Contains(new DateTime(2026, 6, 19, 14, 0, 0), hrLoad.StartTimes);
        Assert.Contains(new DateTime(2026, 6, 19, 15, 0, 0), hrLoad.StartTimes);
        // the day table is loaded once with the involved date.
        var dayLoad = store.Loads.Single(l => l.Table == CdrSummaryType.sum_voice_day_03);
        Assert.Contains(new DateTime(2026, 6, 19), dayLoad.StartTimes);
    }

    [Fact]
    public void Empty_batch_writes_nothing()
    {
        var store = new InMemorySummaryStore();
        var result = CdrProcessor.Default().Process(
            new CdrBatch(Mediation(), RetailPartner5, Array.Empty<cdr>(), store));

        Assert.Equal(0, result.Total);
        Assert.Empty(result.Rated);
        Assert.Empty(store.ExecutedSql);
    }
}
