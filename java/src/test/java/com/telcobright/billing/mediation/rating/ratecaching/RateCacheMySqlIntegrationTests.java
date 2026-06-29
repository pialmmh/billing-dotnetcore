package com.telcobright.billing.mediation.rating.ratecaching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.telcobright.billing.data.MySqlRateLoader;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.servicefamilies.SfA2ZWithVatTax;
import com.telcobright.billing.mediation.servicegroups.ServiceGroupDetection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * INTEGRATION TEST against the local lxc MySQL: the RateCache loads ONE DAY's rates from the
 * rate/rateplanassignmenttuple tables (the legacy PopulateDicByDay), and the legacy PrefixMatcher looks
 * up through it. A future-dated rate proves only the day-valid rates are loaded. Skips if mysql is down.
 *
 * <p>FAITHFUL-PORT NOTE (xUnit -&gt; JUnit 5): the per-test {@code using var conn = TryOpen(); if (conn is
 * null) return;} skip becomes a {@code @BeforeEach} that opens the connection and
 * {@code assumeTrue(conn != null, ...)} so a missing DB ABORTS (skips), never fails.</p>
 */
class RateCacheMySqlIntegrationTests {
    private static final String ServerUrl = "jdbc:mysql://127.0.0.1:3306/";
    private static final String User = "root";
    private static final String Password = "123456";
    private static final String Db = "billing_rate_test";

    private Connection conn;

    private static Connection tryOpen() {
        try {
            return DriverManager.getConnection(ServerUrl, User, Password);
        } catch (Exception e) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        conn = tryOpen();
        assumeTrue(conn != null, "local MySQL not reachable — skipping");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) conn.close();
    }

    private static void Exec(Connection c, String sql) {
        try (Statement cmd = c.createStatement()) {
            cmd.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void CreateSchema(Connection c) {
        Exec(c, "create database if not exists " + Db);
        Exec(c, "use " + Db);
        Exec(c, "drop table if exists rateassign");
        Exec(c, "drop table if exists rateplanassignmenttuple");
        Exec(c, """
                create table rateplanassignmenttuple (
                    id int primary key, idService int, AssignDirection int, idpartner int null, route int null, priority int)""");
        Exec(c, """
                create table rateassign (
                    id int primary key, Prefix int, rateamount decimal(18,6), Resolution int, MinDurationSec float,
                    SurchargeTime int, idrateplan bigint null, Category tinyint null, SubCategory tinyint null,
                    startdate datetime, enddate datetime null, Inactive int, OtherAmount1 float null, OtherAmount3 float null,
                    idrateplanassignmenttuple int null)""");
    }

    @Test
    void Ratecache_loads_one_day_from_mysql_and_prefix_matches() {
        CreateSchema(conn);

        var today = LocalDateTime.of(2026, 6, 19, 0, 0);

        // tuple 1: SG10 customer for partner 5
        Exec(conn, "insert into rateplanassignmenttuple (id, idService, AssignDirection, idpartner, route, priority) values (1, 10, 1, 5, null, 0)");
        // rates for that tuple: 1/17/171 valid today; 9999 starts 2027 (must NOT load for today)
        Exec(conn, """
                insert into rateassign
                    (id, Prefix, rateamount, Resolution, MinDurationSec, SurchargeTime, idrateplan, Category, SubCategory,
                     startdate, enddate, Inactive, OtherAmount1, OtherAmount3, idrateplanassignmenttuple) values
                    (1,    1, 1.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0, 1),
                    (2,   17, 2.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0, 1),
                    (3,  171, 3.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0, 1),
                    (4, 9999, 9.0, 0, 0, 0, 7, 1, 1, '2027-01-01 00:00:00', null, 0, 0, 0, 1)""");

        var cache = new RateCache(new MySqlRateLoader(conn));
        var day = new DateRange(today, today.plusDays(1));
        var tup = new TupleByPeriod();
        tup.IdAssignmentTuple = 1;
        tup.DRange = day;
        tup.Priority = 0;
        var tups = List.of(tup);

        assertEquals(171, new PrefixMatcher(cache, "1712345", 1, 1, tups, today).MatchPrefix().Prefix);   // longest
        assertEquals(17, new PrefixMatcher(cache, "1799999", 1, 1, tups, today).MatchPrefix().Prefix);
        assertEquals(1, new PrefixMatcher(cache, "1500000", 1, 1, tups, today).MatchPrefix().Prefix);
        assertNull(new PrefixMatcher(cache, "9999000", 1, 1, tups, today).MatchPrefix());   // 9999 not loaded (future)

        assertTrue(cache.DateRangeWiseRateDic.containsKey(day));   // the day is now cached
    }

    @Test
    void Cdr_is_rated_by_the_service_family_through_the_ratecache() {
        CreateSchema(conn);

        var today = LocalDateTime.of(2026, 6, 19, 0, 0);

        // SG10 customer tuple for partner 5; rate prefix 1712 @ 1.0/min with a 50% VAT fraction (OtherAmount3)
        Exec(conn, "insert into rateplanassignmenttuple (id, idService, AssignDirection, idpartner, route, priority) values (1, 10, 1, 5, null, 0)");
        Exec(conn, """
                insert into rateassign
                    (id, Prefix, rateamount, Resolution, MinDurationSec, SurchargeTime, idrateplan, Category, SubCategory,
                     startdate, enddate, Inactive, OtherAmount1, OtherAmount3, idrateplanassignmenttuple) values
                    (1, 1712, 1.0, 0, 0, 0, 7, 1, 1, '2026-01-01 00:00:00', null, 0, 0, 0.5, 1)""");

        // the inbound cdr: a retail (SG10) call to 8801712345678
        var thisCdr = new cdr();
        thisCdr.InPartnerId = 5;
        thisCdr.TerminatingCalledNumber = "8801712345678";
        thisCdr.DurationSec = BigDecimal.valueOf(60);
        thisCdr.StartTime = today;
        thisCdr.AnswerTime = today;
        thisCdr.ChargingStatus = 1;
        var partners = Map.of(5, new Partner(5, null, 3));

        // ① detect the service group (SG10) and normalize the number
        var match = ServiceGroupDetection.Default().Detect(thisCdr, partners);
        assertEquals(10, match.ServiceGroupId());

        // ② resolve the tuple → look the rate up THROUGH THE RATECACHE (loaded for today from mysql)
        var cache = new RateCache(new MySqlRateLoader(conn));
        var day = new DateRange(today.toLocalDate().atStartOfDay(), today.toLocalDate().atStartOfDay().plusDays(1));
        var tup = new TupleByPeriod();
        tup.IdAssignmentTuple = 1;
        tup.DRange = day;
        tup.Priority = 0;
        var tups = List.of(tup);
        var rate = new PrefixMatcher(cache, match.NormalizedNumber(), 1, 1, tups, today).MatchPrefix();
        assertEquals(1712, rate.Prefix);

        // ③ rate the cdr with the service family (SF10 = SfA2ZWithVatTax)
        var chargeable = new SfA2ZWithVatTax().Charge(rate, thisCdr, 10, AssignmentDirection.Customer, 8);
        assertEquals(10, chargeable.servicegroup);
        assertEquals(10, chargeable.servicefamily);
        assertEquals(0, new BigDecimal("1.0").compareTo(chargeable.BilledAmount));   // 60s @ 1.0/min
        assertEquals(0, new BigDecimal("0.5").compareTo(chargeable.TaxAmount1));      // 1.0 * 0.5 VAT
        assertEquals("1712", chargeable.Prefix);
    }
}
