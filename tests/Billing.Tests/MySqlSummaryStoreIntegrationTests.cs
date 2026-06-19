using Billing.Data;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using Billing.Mediation.Summary;
using MediationModel;
using MySqlConnector;

namespace Billing.Tests;

/// <summary>
/// INTEGRATION TEST against the local lxc MySQL (127.0.0.1:3306, root/123456). Round-trips the summary
/// GENERATION, LOADING (PopulatePrevSummary reads existing rows from the DB) and INCREMENT/APPEND
/// (merge-add onto the loaded row -> UPDATE) through the real <see cref="MySqlSummaryStore"/>. The test
/// creates its own throwaway schema; it skips (passes as a no-op) if the DB is unreachable so the suite
/// stays runnable without a database.
/// </summary>
public class MySqlSummaryStoreIntegrationTests
{
    private const string ServerConn = "Server=127.0.0.1;Port=3306;User ID=root;Password=123456;";
    private const string Db = "billing_summary_test";

    private static MySqlConnection? TryOpen()
    {
        try { var c = new MySqlConnection(ServerConn); c.Open(); return c; }
        catch { return null; }
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
    public void Generate_load_and_increment_round_trip_through_mysql()
    {
        using var conn = TryOpen();
        if (conn is null) return;   // local mysql not reachable -> skip
        CreateSchema(conn);

        var cdr = Sg10Cdr();
        var charge = Sg10Charge();
        var day = cdr.StartTime.Date;
        var hour = HourOf(cdr.StartTime);

        // ---- round 1: GENERATION + INSERT (empty table, nothing to load) ----
        var ctx1 = new CdrSummaryContext(new MySqlSummaryStore(conn), new CountingAutoIncrementManager(1));
        ctx1.PopulatePrevSummary(new[] { 10 }, day, hour);
        ctx1.AddCall(cdr, charge);
        ctx1.WriteAllChanges();

        Assert.Equal(1L, Convert.ToInt64(Scalar(conn, "select count(*) from sum_voice_day_03")));
        Assert.Equal(1L, Convert.ToInt64(Scalar(conn, "select totalcalls from sum_voice_day_03")));
        Assert.Equal(1.0m, Convert.ToDecimal(Scalar(conn, "select customercost from sum_voice_day_03")));

        // ---- round 2: LOADING + INCREMENT/APPEND (a second call, fresh context) ----
        var ctx2 = new CdrSummaryContext(new MySqlSummaryStore(conn), new CountingAutoIncrementManager(100));
        ctx2.PopulatePrevSummary(new[] { 10 }, day, hour);   // LOADS the row inserted above
        ctx2.AddCall(cdr, charge);                            // merge-add onto it
        ctx2.WriteAllChanges();                               // UPDATE

        Assert.Equal(1L, Convert.ToInt64(Scalar(conn, "select count(*) from sum_voice_day_03")));   // still one row
        Assert.Equal(2L, Convert.ToInt64(Scalar(conn, "select totalcalls from sum_voice_day_03")));  // incremented
        Assert.Equal(2.0m, Convert.ToDecimal(Scalar(conn, "select customercost from sum_voice_day_03"))); // appended
    }

    [Fact]
    public void FinalizeEngine_charges_and_writes_summary_end_to_end()
    {
        using var conn = TryOpen();
        if (conn is null) return;   // local mysql not reachable -> skip
        CreateSchema(conn);

        // mediation: SG10 customer tuple for partner 5 (1.0/min for prefix 1712)
        var mediation = new MediationContext
        {
            RatePlanResolver = RatePlanResolver.Build(new[]
            {
                TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0, TestData.Ra(1712, 1.0m, idRatePlan: 7)),
            }),
        };
        var partners = new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };
        var facts = new FinalizeFacts("admin", "8801999000111", "8801712345678", ServiceType.Voice,
            SwitchId: 1, "in", "out", OutPartnerId: 0, AnswerTime: new DateTime(2026, 6, 19, 14, 30, 0),
            Billsec: 60, Answered: true, "uid-1");
        var tier = new FinalizeTierInput("billing_summary_test", PartnerId: 5, mediation, partners,
            TierMode.Full, new TierReserved(100, "BDT", 5.0m));
        Func<string, ISummaryStore> storeFor = _ => new MySqlSummaryStore(conn);

        var engine = FinalizeEngine.Default();

        // first call: detect SG10 -> charge -> build summary -> INSERT
        var r1 = engine.Finalize(facts, new[] { tier }, storeFor, new CountingAutoIncrementManager(1));
        Assert.True(r1.Success);
        Assert.Equal(1.0m, r1.Settlements["billing_summary_test"].Charged);
        Assert.Equal(1L, Convert.ToInt64(Scalar(conn, "select totalcalls from sum_voice_day_03")));
        Assert.Equal(1.0m, Convert.ToDecimal(Scalar(conn, "select customercost from sum_voice_day_03")));

        // second call (same facts): load existing -> increment -> UPDATE
        engine.Finalize(facts, new[] { tier }, storeFor, new CountingAutoIncrementManager(100));
        Assert.Equal(1L, Convert.ToInt64(Scalar(conn, "select count(*) from sum_voice_day_03")));
        Assert.Equal(2L, Convert.ToInt64(Scalar(conn, "select totalcalls from sum_voice_day_03")));
        Assert.Equal(2.0m, Convert.ToDecimal(Scalar(conn, "select customercost from sum_voice_day_03")));
    }

    private static void CreateSchema(MySqlConnection conn)
    {
        Exec(conn, $"create database if not exists {Db}");
        Exec(conn, $"use {Db}");
        foreach (var table in new[] { "sum_voice_day_03", "sum_voice_hr_03" })
        {
            Exec(conn, $"drop table if exists {table}");
            Exec(conn, CreateTableSql(table));
        }
    }

    private static string CreateTableSql(string table) => $@"create table {table} (
        id bigint primary key,
        tup_switchid int, tup_inpartnerid int, tup_outpartnerid int,
        tup_incomingroute varchar(64), tup_outgoingroute varchar(64),
        tup_customerrate decimal(18,6), tup_supplierrate decimal(18,6),
        tup_incomingip varchar(64), tup_outgoingip varchar(64),
        tup_countryorareacode varchar(32), tup_matchedprefixcustomer varchar(32), tup_matchedprefixsupplier varchar(32),
        tup_sourceId varchar(32), tup_destinationId varchar(32),
        tup_customercurrency varchar(16), tup_suppliercurrency varchar(16), tup_tax1currency varchar(16),
        tup_tax2currency varchar(16), tup_vatcurrency varchar(16),
        tup_starttime datetime,
        totalcalls bigint, connectedcalls bigint, connectedcallsCC bigint, successfulcalls bigint,
        actualduration decimal(18,6), roundedduration decimal(18,6),
        duration1 decimal(18,6), duration2 decimal(18,6), duration3 decimal(18,6), PDD decimal(18,6),
        customercost decimal(18,6), suppliercost decimal(18,6), tax1 decimal(18,6), tax2 decimal(18,6), vat decimal(18,6),
        intAmount1 int, intAmount2 int, longAmount1 bigint, longAmount2 bigint,
        longDecimalAmount1 decimal(18,6), longDecimalAmount2 decimal(18,6),
        intAmount3 int, longAmount3 bigint, longDecimalAmount3 decimal(18,6),
        decimalAmount1 decimal(18,6), decimalAmount2 decimal(18,6), decimalAmount3 decimal(18,6)
    )";

    private static void Exec(MySqlConnection conn, string sql) { using var c = new MySqlCommand(sql, conn); c.ExecuteNonQuery(); }
    private static object? Scalar(MySqlConnection conn, string sql) { using var c = new MySqlCommand(sql, conn); return c.ExecuteScalar(); }
}
