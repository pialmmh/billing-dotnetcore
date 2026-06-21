using Billing.Mediation.Cdr;
using Billing.Mediation.Sql;
using Billing.Mediation.Summary;
using MediationModel;
using MySqlConnector;

namespace Billing.Tests;

/// <summary>INTEGRATION TEST against local lxc MySQL: ChargeableWriter's batched insert (the legacy
/// acc_chargeable ExtInsertColumns + GetExtInsertValues) is accepted by a real acc_chargeable table — ids
/// are assigned, both legs land, and a null tax leg stores SQL NULL. Skips if mysql is unreachable.</summary>
public class ChargeableWriterMySqlIntegrationTests
{
    private const string ServerConn = "Server=127.0.0.1;Port=3306;User ID=root;Password=123456;";
    private const string Db = "billing_chargeable_test";

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
    public void Writes_chargeable_rows_to_mysql()
    {
        using var conn = TryOpen();
        if (conn is null) return;   // local mysql not reachable -> skip

        Exec(conn, $"create database if not exists {Db}");
        Exec(conn, $"use {Db}");
        Exec(conn, "drop table if exists acc_chargeable");
        Exec(conn, @"create table acc_chargeable(
            id bigint primary key, uniqueBillId varchar(64), idEvent bigint, transactionTime datetime,
            assignedDirection tinyint null, description varchar(255), glAccountId bigint, servicegroup int,
            servicefamily int, ProductId bigint, idBilledUom varchar(16), idQuantityUom varchar(16),
            BilledAmount decimal(18,6), Quantity decimal(18,6), unitPriceOrCharge decimal(18,6), Prefix varchar(32),
            RateId bigint, TaxAmount1 decimal(18,6) null, TaxAmount2 decimal(18,6) null, TaxAmount3 decimal(18,6) null,
            VatAmount1 decimal(18,6) null, VatAmount2 decimal(18,6) null, VatAmount3 decimal(18,6) null,
            OtherAmount1 decimal(18,6) null, OtherAmount2 decimal(18,6) null, OtherAmount3 decimal(18,6) null,
            OtherDecAmount1 decimal(18,6) null, OtherDecAmount2 decimal(18,6) null, OtherDecAmount3 decimal(18,6) null,
            createdByJob bigint null, changedByJob bigint null, idBillingrule int, jsonDetail text)");

        var when = new DateTime(2026, 6, 19, 14, 30, 0);
        var chargeables = new List<acc_chargeable>
        {
            new() { servicegroup = 10, servicefamily = 10, BilledAmount = 1.0m, TaxAmount1 = 0.5m, Prefix = "1712",
                    unitPriceOrCharge = 1.0m, idBilledUom = "BDT", transactionTime = when, uniqueBillId = "uid-1", Quantity = 60m },
            new() { servicegroup = 1, servicefamily = 1, BilledAmount = 2.0m, Prefix = "1712", assignedDirection = 2,
                    unitPriceOrCharge = 2.0m, idBilledUom = "BDT", transactionTime = when, uniqueBillId = "uid-1", Quantity = 60m },
        };

        var written = ChargeableWriter.Write(new ConnExecutor(conn), chargeables, new CountingAutoIncrementManager(1));

        Assert.Equal(2, written);                 // both rows inserted in one batched statement
        Assert.Equal(1L, chargeables[0].id);      // ids assigned from the counter
        Assert.Equal(2L, chargeables[1].id);
        Assert.Equal(2L, Convert.ToInt64(Scalar(conn, "select count(*) from acc_chargeable")));
        Assert.Equal(0.5m, Convert.ToDecimal(Scalar(conn, "select TaxAmount1 from acc_chargeable where id=1")));
        Assert.IsType<DBNull>(Scalar(conn, "select TaxAmount1 from acc_chargeable where id=2"));   // null leg → SQL NULL
    }
}
