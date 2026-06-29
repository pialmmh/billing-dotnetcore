package com.telcobright.billing.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.rating.FinalizeEngine;
import com.telcobright.billing.mediation.rating.FinalizeFacts;
import com.telcobright.billing.mediation.rating.FinalizeTierInput;
import com.telcobright.billing.mediation.rating.ServiceType;
import com.telcobright.billing.mediation.rating.TierMode;
import com.telcobright.billing.mediation.rating.TierReserved;
import com.telcobright.billing.mediation.summary.CdrSummaryContext;
import com.telcobright.billing.mediation.summary.CountingAutoIncrementManager;
import com.telcobright.billing.mediation.summary.ISummaryStore;
import com.telcobright.billing.testsupport.TestData;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * INTEGRATION TEST against the local lxc MySQL (127.0.0.1:3306, root/123456). Round-trips the summary
 * GENERATION, LOADING (PopulatePrevSummary reads existing rows from the DB) and INCREMENT/APPEND
 * (merge-add onto the loaded row -&gt; UPDATE) through the real {@link MySqlSummaryStore}. The test
 * creates its own throwaway schema; it skips (passes as a no-op) if the DB is unreachable so the suite
 * stays runnable without a database.
 *
 * <p>FAITHFUL-PORT NOTE (xUnit -&gt; JUnit 5): the per-test {@code using var conn = TryOpen(); if (conn is
 * null) return;} skip becomes a {@code @BeforeEach} that opens the connection and
 * {@code assumeTrue(conn != null, ...)} so a missing DB ABORTS (skips), never fails.</p>
 */
class MySqlSummaryStoreIntegrationTests {
    private static final String ServerUrl = "jdbc:mysql://127.0.0.1:3306/";
    private static final String User = "root";
    private static final String Password = "123456";
    private static final String Db = "billing_summary_test";

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

    private static cdr Sg10Cdr() {
        cdr c = new cdr();
        c.SwitchId = 1;
        c.InPartnerId = 5;
        c.IncomingRoute = "in";
        c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1";
        c.TerminatingIP = "2.2.2.2";
        c.StartTime = LocalDateTime.of(2026, 6, 19, 14, 30, 0);
        c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60);
        c.RoundedDuration = BigDecimal.valueOf(60);
        c.Duration1 = BigDecimal.valueOf(60);
        c.CountryCode = "880";
        c.AnsIdTerm = 42;
        c.MatchedPrefixSupplier = "1712";
        return c;
    }

    private static acc_chargeable Sg10Charge() {
        acc_chargeable c = new acc_chargeable();
        c.servicegroup = 10;
        c.servicefamily = 10;
        c.BilledAmount = new BigDecimal("1.0");
        c.TaxAmount1 = new BigDecimal("0.5");
        c.Prefix = "1712";
        c.unitPriceOrCharge = new BigDecimal("1.0");
        c.idBilledUom = "BDT";
        return c;
    }

    private static LocalDateTime HourOf(LocalDateTime t) {
        return LocalDateTime.of(t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour(), 0, 0);
    }

    @Test
    void Generate_load_and_increment_round_trip_through_mysql() {
        CreateSchema(conn);

        var thisCdr = Sg10Cdr();
        var charge = Sg10Charge();
        var day = thisCdr.StartTime.toLocalDate().atStartOfDay();
        var hour = HourOf(thisCdr.StartTime);

        // ---- round 1: GENERATION + INSERT (empty table, nothing to load) ----
        var ctx1 = new CdrSummaryContext(new MySqlSummaryStore(conn), new CountingAutoIncrementManager(1));
        ctx1.PopulatePrevSummary(List.of(10), List.of(day), List.of(hour));
        ctx1.AddCall(thisCdr, charge);
        ctx1.WriteAllChanges();

        assertEquals(1L, ((Number) Scalar(conn, "select count(*) from sum_voice_day_03")).longValue());
        assertEquals(1L, ((Number) Scalar(conn, "select totalcalls from sum_voice_day_03")).longValue());
        assertEquals(0, new BigDecimal("1.0").compareTo(
                (BigDecimal) Scalar(conn, "select customercost from sum_voice_day_03")));

        // ---- round 2: LOADING + INCREMENT/APPEND (a second call, fresh context) ----
        var ctx2 = new CdrSummaryContext(new MySqlSummaryStore(conn), new CountingAutoIncrementManager(100));
        ctx2.PopulatePrevSummary(List.of(10), List.of(day), List.of(hour));   // LOADS the row inserted above
        ctx2.AddCall(thisCdr, charge);                            // merge-add onto it
        ctx2.WriteAllChanges();                                   // UPDATE

        assertEquals(1L, ((Number) Scalar(conn, "select count(*) from sum_voice_day_03")).longValue());   // still one row
        assertEquals(2L, ((Number) Scalar(conn, "select totalcalls from sum_voice_day_03")).longValue());  // incremented
        assertEquals(0, new BigDecimal("2.0").compareTo(
                (BigDecimal) Scalar(conn, "select customercost from sum_voice_day_03")));                   // appended
    }

    @Test
    void FinalizeEngine_charges_and_writes_summary_end_to_end() {
        CreateSchema(conn);

        // mediation: SG10 customer tuple for partner 5 (1.0/min for prefix 1712)
        var mediation = MediationContext.ForRating(List.of(
                TestData.Tup(10, AssignmentDirection.Customer.value, 5, null, 0,
                        TestData.Ra(1712, "1.0").idRatePlan(7))));
        var partners = Map.of(5, new Partner(5, null, 3));
        var facts = new FinalizeFacts("admin", "8801999000111", "8801712345678", ServiceType.Voice,
                1, "in", "out", 0, LocalDateTime.of(2026, 6, 19, 14, 30, 0),
                60, true, "uid-1");
        var tier = new FinalizeTierInput("billing_summary_test", 5, mediation, partners,
                TierMode.Full, new TierReserved(100, "BDT", new BigDecimal("5.0")));
        Function<String, ISummaryStore> storeFor = dbName -> new MySqlSummaryStore(conn);

        var engine = FinalizeEngine.Default();

        // first call: detect SG10 -> charge -> build summary -> INSERT
        var r1 = engine.Finalize(facts, List.of(tier), storeFor, new CountingAutoIncrementManager(1));
        assertTrue(r1.Success());
        assertEquals(0, new BigDecimal("1.0").compareTo(r1.Settlements().get("billing_summary_test").Charged()));
        assertEquals(1L, ((Number) Scalar(conn, "select totalcalls from sum_voice_day_03")).longValue());
        assertEquals(0, new BigDecimal("1.0").compareTo(
                (BigDecimal) Scalar(conn, "select customercost from sum_voice_day_03")));

        // second call (same facts): load existing -> increment -> UPDATE
        engine.Finalize(facts, List.of(tier), storeFor, new CountingAutoIncrementManager(100));
        assertEquals(1L, ((Number) Scalar(conn, "select count(*) from sum_voice_day_03")).longValue());
        assertEquals(2L, ((Number) Scalar(conn, "select totalcalls from sum_voice_day_03")).longValue());
        assertEquals(0, new BigDecimal("2.0").compareTo(
                (BigDecimal) Scalar(conn, "select customercost from sum_voice_day_03")));
    }

    private static void CreateSchema(Connection conn) {
        Exec(conn, "create database if not exists " + Db);
        Exec(conn, "use " + Db);
        for (var table : new String[] { "sum_voice_day_03", "sum_voice_hr_03" }) {
            Exec(conn, "drop table if exists " + table);
            Exec(conn, CreateTableSql(table));
        }
    }

    private static String CreateTableSql(String table) {
        return """
                create table %s (
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
                )""".formatted(table);
    }

    private static void Exec(Connection conn, String sql) {
        try (Statement c = conn.createStatement()) {
            c.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object Scalar(Connection conn, String sql) {
        try (Statement c = conn.createStatement();
             ResultSet rs = c.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
