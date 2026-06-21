using Billing.Mediation.Summary;
using MediationModel;
using MediationModel.enums;
using TelcobrightMediation;

namespace Billing.Tests;

/// <summary>The full summary orchestration end to end over an in-memory store: PopulatePrevSummary →
/// GenerateSummary → MergeAdd → WriteAllChanges. A fresh call inserts day+hr rows; a call whose day row
/// already exists merges onto it and updates.</summary>
public class CdrSummaryContextTests
{
    private sealed class InMemorySummaryStore : ISummaryStore
    {
        private readonly Dictionary<CdrSummaryType, List<AbstractCdrSummary>> _rows = new();
        public List<string> ExecutedSql { get; } = new();

        public void Seed(CdrSummaryType table, AbstractCdrSummary row)
        {
            if (!_rows.TryGetValue(table, out var list)) _rows[table] = list = new();
            list.Add(row);
        }

        public IReadOnlyList<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, IReadOnlyCollection<DateTime> startTimes) =>
            _rows.TryGetValue(table, out var list)
                ? list.Where(r => startTimes.Contains(r.tup_starttime)).ToList()
                : new List<AbstractCdrSummary>();

        public int ExecuteNonQuery(string sql) { ExecutedSql.Add(sql); return 1; }
    }

    private static cdr Sg10Cdr() => new()
    {
        SwitchId = 1, InPartnerId = 5, IncomingRoute = "in", OutgoingRoute = "out",
        OriginatingIP = "1.1.1.1", TerminatingIP = "2.2.2.2",
        StartTime = new DateTime(2026, 6, 19, 14, 30, 0), ChargingStatus = 1,
        DurationSec = 60m, RoundedDuration = 60m, Duration1 = 60m,
        CountryCode = "880", AnsIdTerm = 42, MatchedPrefixSupplier = "1712",
    };

    private static acc_chargeable Sg10Charge() => new()
    {
        servicegroup = 10, servicefamily = 10,
        BilledAmount = 1.0m, TaxAmount1 = 0.5m, Prefix = "1712", unitPriceOrCharge = 1.0m, idBilledUom = "BDT",
    };

    private static DateTime HourOf(DateTime t) => new(t.Year, t.Month, t.Day, t.Hour, 0, 0);

    [Fact]
    public void Fresh_call_inserts_day_and_hour_rows()
    {
        var store = new InMemorySummaryStore();
        var ctx = new CdrSummaryContext(store, new CountingAutoIncrementManager(1000));
        var cdr = Sg10Cdr();

        ctx.PopulatePrevSummary(new[] { 10 }, new[] { cdr.StartTime.Date }, new[] { HourOf(cdr.StartTime) });
        ctx.AddCall(cdr, Sg10Charge());
        ctx.WriteAllChanges();

        Assert.Equal(2, store.ExecutedSql.Count);
        Assert.Contains(store.ExecutedSql, s => s.StartsWith("insert into sum_voice_day_03"));
        Assert.Contains(store.ExecutedSql, s => s.StartsWith("insert into sum_voice_hr_03"));
    }

    [Fact]
    public void Existing_day_row_is_merged_onto_and_updated()
    {
        var cdr = Sg10Cdr();
        var charge = Sg10Charge();

        // the existing persisted day row = what this call builds, but already in the DB (id 100, one call)
        var existingDay = CdrSummaryBuilder.Build(cdr, charge, SummaryBucket.Day);
        existingDay.id = 100;
        var store = new InMemorySummaryStore();
        store.Seed(CdrSummaryType.sum_voice_day_03, existingDay);

        var ctx = new CdrSummaryContext(store, new CountingAutoIncrementManager(1000));
        ctx.PopulatePrevSummary(new[] { 10 }, new[] { cdr.StartTime.Date }, new[] { HourOf(cdr.StartTime) });
        ctx.AddCall(cdr, charge);
        ctx.WriteAllChanges();

        // day → UPDATE the existing row 100; hr → INSERT (no prev)
        Assert.Contains(store.ExecutedSql, s => s.StartsWith("update sum_voice_day_03") && s.Contains("where id=100"));
        Assert.Contains(store.ExecutedSql, s => s.StartsWith("insert into sum_voice_hr_03"));

        var dayRow = ctx.TableWiseSummaryCache[CdrSummaryType.sum_voice_day_03].GetItems().Single();
        Assert.Equal(2, dayRow.totalcalls);        // 1 existing + 1 this call
        Assert.Equal(2.0m, dayRow.customercost);   // 1.0 + 1.0
    }
}
