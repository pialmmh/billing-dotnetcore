using Billing.Data;
using Billing.Mediation.Model;
using Billing.Mediation.ServiceFamilies;
using Billing.Mediation.ServiceGroups;
using LibraryExtensions;
using MediationModel;
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

    [Fact]
    public void Cdr_is_rated_by_the_service_family_through_the_ratecache()
    {
        using var conn = TryOpen();
        if (conn is null) return;
        CreateSchema(conn);

        var today = new DateTime(2026, 6, 19);

        // SG10 customer tuple for partner 5; rate prefix 1712 @ 1.0/min with a 50% VAT fraction (OtherAmount3)
        Exec(conn, "insert into rateplanassignmenttuple (id, idService, AssignDirection, idpartner, route, priority) values (1, 10, 1, 5, null, 0)");
        Exec(conn, @"insert into rateassign
            (id, Prefix, rateamount, Resolution, MinDurationSec, SurchargeTime, idrateplan, Category, SubCategory,
             startdate, enddate, Inactive, OtherAmount1, OtherAmount3, idrateplanassignmenttuple) values
            (1, 1712, 1.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0.5, 1)");

        // the inbound cdr: a retail (SG10) call to 8801712345678
        var thisCdr = new cdr
        {
            InPartnerId = 5, TerminatingCalledNumber = "8801712345678",
            DurationSec = 60m, StartTime = today, AnswerTime = today, ChargingStatus = 1,
        };
        var partners = new Dictionary<int, Partner> { [5] = new() { IdPartner = 5, PartnerType = 3 } };

        // ① detect the service group (SG10) and normalize the number
        var match = ServiceGroupDetection.Default().Detect(thisCdr, partners);
        Assert.Equal(10, match!.Value.ServiceGroupId);

        // ② resolve the tuple → look the rate up THROUGH THE RATECACHE (loaded for today from mysql)
        var cache = new RateCache(new MySqlRateLoader(conn));
        var day = new DateRange(today.Date, today.Date.AddDays(1));
        var tups = new List<TupleByPeriod> { new() { IdAssignmentTuple = 1, DRange = day, Priority = 0 } };
        var rate = new PrefixMatcher(cache, match.Value.NormalizedNumber, 1, 1, tups, today).MatchPrefix();
        Assert.Equal(1712, rate!.Prefix);

        // ③ rate the cdr with the service family (SF10 = SfA2ZWithVatTax)
        var chargeable = new SfA2ZWithVatTax().Charge(rate, thisCdr, serviceGroupId: 10, AssignmentDirection.Customer, 8);
        Assert.Equal(10, chargeable.servicegroup);
        Assert.Equal(10, chargeable.servicefamily);
        Assert.Equal(1.0m, chargeable.BilledAmount);   // 60s @ 1.0/min
        Assert.Equal(0.5m, chargeable.TaxAmount1);      // 1.0 * 0.5 VAT
        Assert.Equal("1712", chargeable.Prefix);
    }
}
