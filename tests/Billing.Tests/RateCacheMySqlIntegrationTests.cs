using Billing.Data;
using LibraryExtensions;
using MySqlConnector;
using TelcobrightMediation;

namespace Billing.Tests;

/// <summary>INTEGRATION TEST against the local lxc MySQL: the RateCache loads ONE DAY's rates from the
/// rate/rateplanassignmenttuple tables (the legacy PopulateDicByDay), and the legacy PrefixMatcher looks
/// up through it. A future-dated rate proves only the day-valid rates are loaded. Skips if mysql is down.</summary>
public class RateCacheMySqlIntegrationTests
{
    private const string ServerConn = "Server=127.0.0.1;Port=3306;User ID=root;Password=123456;";
    private const string Db = "billing_rate_test";

    private static MySqlConnection? TryOpen()
    {
        try { var c = new MySqlConnection(ServerConn); c.Open(); return c; } catch { return null; }
    }

    private static void Exec(MySqlConnection c, string sql) { using var cmd = new MySqlCommand(sql, c); cmd.ExecuteNonQuery(); }

    private static void CreateSchema(MySqlConnection c)
    {
        Exec(c, $"create database if not exists {Db}");
        Exec(c, $"use {Db}");
        Exec(c, "drop table if exists rateassign");
        Exec(c, "drop table if exists rateplanassignmenttuple");
        Exec(c, @"create table rateplanassignmenttuple (
            id int primary key, idService int, AssignDirection int, idpartner int null, route int null, priority int)");
        Exec(c, @"create table rateassign (
            id int primary key, Prefix int, rateamount decimal(18,6), Resolution int, MinDurationSec float,
            SurchargeTime int, idrateplan bigint null, Category tinyint null, SubCategory tinyint null,
            startdate datetime, enddate datetime null, Inactive int, OtherAmount1 float null, OtherAmount3 float null,
            idrateplanassignmenttuple int null)");
    }

    [Fact]
    public void Ratecache_loads_one_day_from_mysql_and_prefix_matches()
    {
        using var conn = TryOpen();
        if (conn is null) return;   // local mysql not reachable -> skip
        CreateSchema(conn);

        var today = new DateTime(2026, 6, 19);

        // tuple 1: SG10 customer for partner 5
        Exec(conn, "insert into rateplanassignmenttuple (id, idService, AssignDirection, idpartner, route, priority) values (1, 10, 1, 5, null, 0)");
        // rates for that tuple: 1/17/171 valid today; 9999 starts 2027 (must NOT load for today)
        Exec(conn, @"insert into rateassign
            (id, Prefix, rateamount, Resolution, MinDurationSec, SurchargeTime, idrateplan, Category, SubCategory,
             startdate, enddate, Inactive, OtherAmount1, OtherAmount3, idrateplanassignmenttuple) values
            (1,    1, 1.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0, 1),
            (2,   17, 2.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0, 1),
            (3,  171, 3.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0, 1),
            (4, 9999, 9.0, 0, 0, 0, 7, 1, 1, '2027-01-01 00:00:00', null, 0, 0, 0, 1)");

        var cache = new RateCache(new MySqlRateLoader(conn));
        var day = new DateRange(today, today.AddDays(1));
        var tups = new List<TupleByPeriod> { new() { IdAssignmentTuple = 1, DRange = day, Priority = 0 } };

        Assert.Equal(171, new PrefixMatcher(cache, "1712345", 1, 1, tups, today).MatchPrefix().Prefix);   // longest
        Assert.Equal(17, new PrefixMatcher(cache, "1799999", 1, 1, tups, today).MatchPrefix().Prefix);
        Assert.Equal(1, new PrefixMatcher(cache, "1500000", 1, 1, tups, today).MatchPrefix().Prefix);
        Assert.Null(new PrefixMatcher(cache, "9999000", 1, 1, tups, today).MatchPrefix());   // 9999 not loaded (future)

        Assert.True(cache.DateRangeWiseRateDic.ContainsKey(day));   // the day is now cached
    }
}
