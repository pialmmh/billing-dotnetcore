using System.Linq;
using Billing.Mediation.Cdr;
using Billing.Mediation.Sql;
using MediationModel;
using MySqlConnector;

namespace Billing.Tests;

/// <summary>The cdr row write: the ported ICacheble&lt;cdr&gt; insert (104 cols) — column/value arity parity
/// (a hand-port guard) and, against local lxc MySQL, a real segmented insert with a null float leg as NULL.</summary>
public class CdrWriterTests
{
    private static cdr SampleCdr(string billId, DateTime when) => new()
    {
        SwitchId = 1, IdCall = 1001, SequenceNumber = 1, ServiceGroup = 10,
        IncomingRoute = "in", OutgoingRoute = "out", OriginatingIP = "1.1.1.1", TerminatingIP = "2.2.2.2",
        TerminatingCalledNumber = "8801712345678", OriginatingCallingNumber = "8801999000111",
        DurationSec = 60m, RoundedDuration = 60m, Duration1 = 60m,
        StartTime = when, EndTime = when, AnswerTime = when, SignalingStartTime = when,
        ChargingStatus = 1, CountryCode = "880", InPartnerId = 5, UniqueBillId = billId,
        Category = 1, SubCategory = 1,   // PDD left null (float?) on purpose -> proves null rendering
    };

    [Fact]
    public void Insert_columns_and_values_have_the_same_arity()
    {
        var columns = cdr.ExtInsertColumns.Split(',').Length;
        var tuple = SampleCdr("uid-1", new DateTime(2026, 6, 19, 14, 30, 0)).GetExtInsertValues().ToString();
        var fields = tuple.Substring(1, tuple.Length - 2).Split(',').Length;   // strip the wrapping ( )

        Assert.Equal(104, columns);
        Assert.Equal(columns, fields);   // header and value tuple must line up
    }

    // ---- integration (skips if local mysql is unreachable) ----
    private const string ServerConn = "Server=127.0.0.1;Port=3306;User ID=root;Password=123456;";
    private const string Db = "billing_cdr_test";

    private sealed class ConnExecutor : ISqlExecutor
    {
        private readonly MySqlConnection _c;
        public ConnExecutor(MySqlConnection c) => _c = c;
        public int ExecuteNonQuery(string sql) { using var cmd = new MySqlCommand(sql, _c); return cmd.ExecuteNonQuery(); }
    }

    private static MySqlConnection? TryOpen()
    {
        try { var c = new MySqlConnection(ServerConn); c.Open(); return c; } catch { return null; }
    }

    private static void Exec(MySqlConnection c, string sql) { using var cmd = new MySqlCommand(sql, c); cmd.ExecuteNonQuery(); }
    private static object Scalar(MySqlConnection c, string sql) { using var cmd = new MySqlCommand(sql, c); return cmd.ExecuteScalar()!; }

    [Fact]
    public void Writes_cdr_rows_to_mysql()
    {
        using var conn = TryOpen();
        if (conn is null) return;   // local mysql not reachable -> skip

        Exec(conn, $"create database if not exists {Db}");
        Exec(conn, $"use {Db}");
        Exec(conn, "drop table if exists cdr");
        // permissive schema (all columns TEXT NULL) — a column-count + value-formatting smoke test of the
        // 104-col insert; the real typed schema is the operator DB. TEXT keeps the row within InnoDB limits.
        var cols = string.Join(", ", cdr.ExtInsertColumns.Split(',').Select(c => $"`{c}` text null"));
        Exec(conn, $"create table cdr ({cols})");

        var when = new DateTime(2026, 6, 19, 14, 30, 0);
        var written = CdrWriter.Write(new ConnExecutor(conn), new[] { SampleCdr("uid-1", when), SampleCdr("uid-2", when) });

        Assert.Equal(2, written);
        Assert.Equal(2L, Convert.ToInt64(Scalar(conn, "select count(*) from cdr")));
        Assert.Equal("8801712345678", Scalar(conn, "select TerminatingCalledNumber from cdr where UniqueBillId='uid-1'").ToString());
        Assert.IsType<DBNull>(Scalar(conn, "select PDD from cdr where UniqueBillId='uid-1'"));   // null float -> SQL NULL
    }
}
