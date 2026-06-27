using Billing.Mediation.Cdr;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Summary;
using MediationModel;
using MediationModel.enums;

namespace Billing.Tests;

/// <summary>The OUTBOX summary path: in <see cref="SummaryMode.Outbox"/> the batch writes ONE compressed
/// <c>summary_affected</c> row (the rated cdrs + their customer chargeable) INSTEAD of rolling up + writing
/// sum_voice_* inline — and it does NOT pre-load any summary bucket. The blob round-trips (the summary-service
/// will base64→gunzip→JSON it back). The cdr + chargeable writes are unchanged, so it's all one transaction.</summary>
public class SummaryOutboxWriterTests
{
    private sealed class InMemorySummaryStore : ISummaryStore
    {
        public List<string> ExecutedSql { get; } = new();
        public List<CdrSummaryType> Loads { get; } = new();
        public IReadOnlyList<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, IReadOnlyCollection<DateTime> startTimes)
        {
            Loads.Add(table);
            return new List<AbstractCdrSummary>();
        }
        public int ExecuteNonQuery(string sql) { ExecutedSql.Add(sql); return 1; }
    }

    private static MediationContext Mediation() => MediationContext.ForRating(new[]
    {
        TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0, TestData.Ra(1712, 1.0m, idRatePlan: 7)),
    });

    private static readonly IReadOnlyDictionary<int, Partner> RetailPartner5 =
        new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };

    private static cdr Call(string called, DateTime when) => new()
    {
        SwitchId = 1, InPartnerId = 5, IncomingRoute = "in", OutgoingRoute = "out",
        OriginatingIP = "1.1.1.1", TerminatingIP = "2.2.2.2",
        TerminatingCalledNumber = called, StartTime = when, AnswerTime = when, ChargingStatus = 1,
        DurationSec = 60m, RoundedDuration = 60m, Duration1 = 60m, CountryCode = "880",
        AnsIdTerm = 42, MatchedPrefixSupplier = "1712",
    };

    private static string ExtractBase64(string outboxInsert)
    {
        const string marker = "values ('cdr', '";
        var start = outboxInsert.IndexOf(marker, StringComparison.Ordinal) + marker.Length;
        return outboxInsert.Substring(start, outboxInsert.Length - start - 2);   // strip trailing ')
    }

    [Fact]
    public void Outbox_mode_writes_one_summary_affected_row_and_skips_inline_summaries()
    {
        var store = new InMemorySummaryStore();
        var when = new DateTime(2026, 6, 19, 14, 30, 0);
        var batch = new CdrBatch(Mediation(), RetailPartner5, new[]
        {
            Call("8801712345678", when),
            Call("8801712000000", when),
        }, store, Summary: SummaryMode.Outbox);

        var result = CdrProcessor.Default().Process(batch);

        Assert.Equal(2, result.Rated.Count);
        // cdr + chargeable writes still happen (same transaction).
        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into cdr (")));
        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into acc_chargeable")));
        // EXACTLY one outbox row; NO inline sum_voice writes; NO bucket pre-load.
        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into summary_affected")));
        Assert.DoesNotContain(store.ExecutedSql, s => s.Contains("sum_voice_"));
        Assert.Empty(store.Loads);
    }

    [Fact]
    public void Inline_mode_is_unchanged_and_writes_no_outbox_row()
    {
        var store = new InMemorySummaryStore();
        var when = new DateTime(2026, 6, 19, 14, 30, 0);
        // default Summary = Inline
        var batch = new CdrBatch(Mediation(), RetailPartner5, new[] { Call("8801712345678", when) }, store);

        CdrProcessor.Default().Process(batch);

        Assert.Equal(1, store.ExecutedSql.Count(s => s.StartsWith("insert into sum_voice_day_03")));
        Assert.DoesNotContain(store.ExecutedSql, s => s.StartsWith("insert into summary_affected"));
    }

    [Fact]
    public void Outbox_blob_round_trips_the_cdrs_and_their_customer_chargeable()
    {
        var store = new InMemorySummaryStore();
        var when = new DateTime(2026, 6, 19, 14, 30, 0);
        var batch = new CdrBatch(Mediation(), RetailPartner5, new[]
        {
            Call("8801712345678", when),
            Call("8801712000000", when),
        }, store, Summary: SummaryMode.Outbox);

        CdrProcessor.Default().Process(batch);

        var outbox = store.ExecutedSql.Single(s => s.StartsWith("insert into summary_affected"));
        var decoded = SummaryOutboxWriter.Decode(ExtractBase64(outbox));

        Assert.Equal(2, decoded.Count);
        Assert.Equal("8801712345678", decoded[0].Cdr.TerminatingCalledNumber);
        Assert.Equal(1, decoded[0].Cdr.SwitchId);
        Assert.Equal(60m, decoded[0].Cdr.DurationSec);
        Assert.Equal(when, decoded[0].Cdr.StartTime);
        // the customer-leg chargeable rode along (servicegroup + billed amount the summary builder reads).
        Assert.NotNull(decoded[0].Customer);
        Assert.Equal(10, decoded[0].Customer!.servicegroup);
        Assert.Equal((sbyte)AssignmentDirection.Customer, decoded[0].Customer!.assignedDirection);
    }

    [Fact]
    public void Encode_decode_is_a_pure_round_trip()
    {
        var cdr = Call("8801712345678", new DateTime(2026, 6, 19, 14, 30, 0));
        cdr.UniqueBillId = "bill-1";
        var chargeable = new acc_chargeable
        {
            servicegroup = 10,
            BilledAmount = 1.5m,
            assignedDirection = (sbyte)AssignmentDirection.Customer,
            Prefix = "1712",
        };
        var rated = new[] { new RatedCdr(cdr, new[] { chargeable }) };

        var decoded = SummaryOutboxWriter.Decode(SummaryOutboxWriter.Encode(rated));

        Assert.Single(decoded);
        Assert.Equal("bill-1", decoded[0].Cdr.UniqueBillId);
        Assert.Equal(1.5m, decoded[0].Customer!.BilledAmount);
        Assert.Equal("1712", decoded[0].Customer!.Prefix);
    }
}
