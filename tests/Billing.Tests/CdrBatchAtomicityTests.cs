using System.Linq;
using Billing.Data;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using MediationModel;
using MySqlConnector;

namespace Billing.Tests;

/// <summary>INTEGRATION (local lxc MySQL): the WHOLE cdr batch is ONE transaction owned at the top level
/// (<see cref="MySqlCdrBatchRunner"/>) — no inner class commits/rolls back. Success commits cdr + chargeable
/// + summaries together; a mid-batch failure rolls back EVERYTHING (the already-written cdr row is undone).
/// Skips if mysql is unreachable.</summary>
public class CdrBatchAtomicityTests
{
    private const string ServerConn = "Server=127.0.0.1;Port=3306;User ID=root;Password=123456;";
    private const string Db = "billing_atomicity_test";

    private static MySqlConnection? TryOpen()
    {
        try { var c = new MySqlConnection(ServerConn); c.Open(); return c; } catch { return null; }
    }

    private static void Exec(MySqlConnection c, string sql) { using var cmd = new MySqlCommand(sql, c); cmd.ExecuteNonQuery(); }
    private static long Count(MySqlConnection c, string table)
    { using var cmd = new MySqlCommand($"select count(*) from {table}", c); return Convert.ToInt64(cmd.ExecuteScalar()); }

    private static readonly IReadOnlyDictionary<int, Partner> Retail5 =
        new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };

    private static MediationContext Mediation() => MediationContext.ForRating(new[]
    {
        TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0, TestData.Ra(1712, 1.0m, idRatePlan: 7)),
    });

    // a rate-able (SG10) + summary-ready call.
    private static cdr Call(string billId, DateTime when) => new()
    {
        SwitchId = 1, InPartnerId = 5, IncomingRoute = "in", OutgoingRoute = "out",
        OriginatingIP = "1.1.1.1", TerminatingIP = "2.2.2.2",
        TerminatingCalledNumber = "8801712345678", OriginatingCallingNumber = "8801999000111",
        StartTime = when, AnswerTime = when, ChargingStatus = 1,
        DurationSec = 60m, RoundedDuration = 60m, Duration1 = 60m, CountryCode = "880",
        UniqueBillId = billId, Category = 1, SubCategory = 1, MatchedPrefixSupplier = "1712",
    };

    private static void CreateDb(MySqlConnection c)
    {
        Exec(c, $"create database if not exists {Db}");
        Exec(c, $"use {Db}");
    }

    // permissive (all-TEXT) cdr / acc_chargeable tables — this proves the transaction boundary, not the schema.
    private static void CreatePermissive(MySqlConnection c, string table, string columns)
    {
        Exec(c, $"drop table if exists {table}");
        var cols = string.Join(", ", columns.Split(',').Select(col => $"`{col}` text null"));
        Exec(c, $"create table {table} ({cols})");
    }

    private static void CreateSummaryTables(MySqlConnection c)
    {
        // both SGs' tables — the pipeline pre-loads every configured SG (SG10→*_03, SG11→*_02).
        foreach (var t in new[] { "sum_voice_day_02", "sum_voice_hr_02", "sum_voice_day_03", "sum_voice_hr_03" })
        {
            Exec(c, $"drop table if exists {t}");
            Exec(c, $@"create table {t} (
                id bigint primary key, tup_switchid int, tup_inpartnerid int, tup_outpartnerid int,
                tup_incomingroute varchar(64), tup_outgoingroute varchar(64),
                tup_customerrate decimal(18,6), tup_supplierrate decimal(18,6),
                tup_incomingip varchar(64), tup_outgoingip varchar(64),
                tup_countryorareacode varchar(32), tup_matchedprefixcustomer varchar(32), tup_matchedprefixsupplier varchar(32),
                tup_sourceId varchar(32), tup_destinationId varchar(32),
                tup_customercurrency varchar(16), tup_suppliercurrency varchar(16), tup_tax1currency varchar(16),
                tup_tax2currency varchar(16), tup_vatcurrency varchar(16), tup_starttime datetime,
                totalcalls bigint, connectedcalls bigint, connectedcallsCC bigint, successfulcalls bigint,
                actualduration decimal(18,6), roundedduration decimal(18,6),
                duration1 decimal(18,6), duration2 decimal(18,6), duration3 decimal(18,6), PDD decimal(18,6),
                customercost decimal(18,6), suppliercost decimal(18,6), tax1 decimal(18,6), tax2 decimal(18,6), vat decimal(18,6),
                intAmount1 int, intAmount2 int, longAmount1 bigint, longAmount2 bigint,
                longDecimalAmount1 decimal(18,6), longDecimalAmount2 decimal(18,6),
                intAmount3 int, longAmount3 bigint, longDecimalAmount3 decimal(18,6),
                decimalAmount1 decimal(18,6), decimalAmount2 decimal(18,6), decimalAmount3 decimal(18,6))");
        }
    }

    [Fact]
    public void Batch_commits_atomically_on_success()
    {
        using var conn = TryOpen();
        if (conn is null) return;
        CreateDb(conn);
        CreatePermissive(conn, "cdr", cdr.ExtInsertColumns);
        CreatePermissive(conn, "acc_chargeable", acc_chargeable.ExtInsertColumns);
        CreateSummaryTables(conn);

        var result = MySqlCdrBatchRunner.Default().Run(conn, Mediation(), Retail5,
            new[] { Call("uid-1", new DateTime(2026, 6, 19, 14, 30, 0)) });

        Assert.Single(result.Rated);
        Assert.Equal(1L, Count(conn, "cdr"));               // committed together
        Assert.Equal(1L, Count(conn, "acc_chargeable"));
        Assert.Equal(1L, Count(conn, "sum_voice_day_03"));
    }

    [Fact]
    public void Batch_rolls_back_entirely_on_a_mid_batch_failure()
    {
        using var conn = TryOpen();
        if (conn is null) return;
        CreateDb(conn);
        CreatePermissive(conn, "cdr", cdr.ExtInsertColumns);
        CreateSummaryTables(conn);
        Exec(conn, "drop table if exists acc_chargeable");   // missing → the chargeable write (AFTER the cdr write) throws

        // the cdr row writes first, then the chargeable write fails → the top-level runner rolls back the batch.
        Assert.ThrowsAny<Exception>(() => MySqlCdrBatchRunner.Default().Run(conn, Mediation(), Retail5,
            new[] { Call("uid-1", new DateTime(2026, 6, 19, 14, 30, 0)) }));

        Assert.Equal(0L, Count(conn, "cdr"));               // the already-written cdr row was rolled back
        Assert.Equal(0L, Count(conn, "sum_voice_day_03"));  // nothing else persisted either
    }
}
