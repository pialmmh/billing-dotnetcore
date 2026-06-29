package com.telcobright.billing.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.telcobright.billing.mediation.cdr.CdrBatchResult;
import com.telcobright.billing.mediation.cdr.SummaryMode;
import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.testsupport.TestData;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * INTEGRATION (local lxc MySQL): the WHOLE cdr batch is ONE transaction owned at the top level
 * ({@link MySqlCdrBatchRunner}) — no inner class commits/rolls back. Success commits cdr + chargeable
 * + summaries together; a mid-batch failure rolls back EVERYTHING (the already-written cdr row is undone).
 * Skips if mysql is unreachable.
 *
 * <p>FAITHFUL-PORT NOTE (xUnit -&gt; JUnit 5): the C# per-test {@code using var conn = TryOpen(); if (conn is
 * null) return;} skip becomes a {@code @BeforeEach} that opens the connection and
 * {@code assumeTrue(conn != null, ...)} — a missing DB ABORTS (skips), never fails. The per-test schema
 * setup stays inside each test (it differs per test, exactly like the C#).</p>
 */
class CdrBatchAtomicityTests {
    private static final String ServerUrl = "jdbc:mysql://127.0.0.1:3306/";
    private static final String User = "root";
    private static final String Password = "123456";
    private static final String Db = "billing_atomicity_test";

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

    private static long Count(Connection c, String table) {
        try (Statement cmd = c.createStatement();
             ResultSet rs = cmd.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static final Map<Integer, Partner> Retail5 =
            Map.of(5, new Partner(5, null, 3));

    private static MediationContext Mediation() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(1712, "1.0").idRatePlan(7));
        return f.mediation();
    }

    // a rate-able (SG10) + summary-ready call.
    private static cdr Call(String billId, LocalDateTime when) {
        cdr c = new cdr();
        c.SwitchId = 1;
        c.InPartnerId = 5;
        c.IncomingRoute = "in";
        c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1";
        c.TerminatingIP = "2.2.2.2";
        c.TerminatingCalledNumber = "8801712345678";
        c.OriginatingCallingNumber = "8801999000111";
        c.StartTime = when;
        c.AnswerTime = when;
        c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60);
        c.RoundedDuration = BigDecimal.valueOf(60);
        c.Duration1 = BigDecimal.valueOf(60);
        c.CountryCode = "880";
        c.UniqueBillId = billId;
        c.Category = 1;
        c.SubCategory = 1;
        c.MatchedPrefixSupplier = "1712";
        return c;
    }

    private static void CreateDb(Connection c) {
        Exec(c, "create database if not exists " + Db);
        Exec(c, "use " + Db);
    }

    // permissive (all-TEXT) cdr / acc_chargeable tables — this proves the transaction boundary, not the schema.
    private static void CreatePermissive(Connection c, String table, String columns) {
        Exec(c, "drop table if exists " + table);
        var cols = Arrays.stream(columns.split(","))
                .map(col -> "`" + col + "` text null")
                .collect(Collectors.joining(", "));
        Exec(c, "create table " + table + " (" + cols + ")");
    }

    private static void CreateSummaryTables(Connection c) {
        // both SGs' tables — the pipeline pre-loads every configured SG (SG10→*_03, SG11→*_02).
        for (var t : new String[] { "sum_voice_day_02", "sum_voice_hr_02", "sum_voice_day_03", "sum_voice_hr_03" }) {
            Exec(c, "drop table if exists " + t);
            Exec(c, CreateTableSql(t));
        }
    }

    private static String CreateTableSql(String table) {
        return """
                create table %s (
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
                    decimalAmount1 decimal(18,6), decimalAmount2 decimal(18,6), decimalAmount3 decimal(18,6))""".formatted(table);
    }

    @Test
    void Batch_commits_atomically_on_success() {
        CreateDb(conn);
        CreatePermissive(conn, "cdr", cdr.ExtInsertColumns);
        CreatePermissive(conn, "acc_chargeable", acc_chargeable.ExtInsertColumns);
        CreateSummaryTables(conn);

        var result = MySqlCdrBatchRunner.Default().Run(conn, Mediation(), Retail5,
                List.of(Call("uid-1", LocalDateTime.of(2026, 6, 19, 14, 30, 0))));

        assertEquals(1, result.Rated().size());
        assertEquals(1L, Count(conn, "cdr"));               // committed together
        assertEquals(1L, Count(conn, "acc_chargeable"));
        assertEquals(1L, Count(conn, "sum_voice_day_03"));
    }

    @Test
    void Batch_rolls_back_entirely_on_a_mid_batch_failure() {
        CreateDb(conn);
        CreatePermissive(conn, "cdr", cdr.ExtInsertColumns);
        CreateSummaryTables(conn);
        Exec(conn, "drop table if exists acc_chargeable");   // missing → the chargeable write (AFTER the cdr write) throws

        // the cdr row writes first, then the chargeable write fails → the top-level runner rolls back the batch.
        assertThrows(Exception.class, () -> MySqlCdrBatchRunner.Default().Run(conn, Mediation(), Retail5,
                List.of(Call("uid-1", LocalDateTime.of(2026, 6, 19, 14, 30, 0)))));

        assertEquals(0L, Count(conn, "cdr"));               // the already-written cdr row was rolled back
        assertEquals(0L, Count(conn, "sum_voice_day_03"));  // nothing else persisted either
    }

    private static void CreateOutboxTable(Connection c) {
        Exec(c, "drop table if exists summary_affected");
        Exec(c, "create table summary_affected (id bigint not null auto_increment, " +
                "entity_type varchar(32) not null, data longtext not null, primary key (id))");
    }

    private static String FirstOutboxData(Connection c) {
        try (Statement cmd = c.createStatement();
             ResultSet rs = cmd.executeQuery("select data from summary_affected order by id limit 1")) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void Outbox_mode_writes_the_batch_atomically_and_skips_inline_summaries() {
        CreateDb(conn);
        CreatePermissive(conn, "cdr", cdr.ExtInsertColumns);
        CreatePermissive(conn, "acc_chargeable", acc_chargeable.ExtInsertColumns);
        CreateOutboxTable(conn);
        // NOTE: deliberately NO sum_voice_* tables — outbox mode must not touch them.

        var result = MySqlCdrBatchRunner.Default().Run(conn, Mediation(), Retail5,
                List.of(Call("uid-1", LocalDateTime.of(2026, 6, 19, 14, 30, 0))),
                null, BatchSqlWriter.DefaultSegmentSize, SummaryMode.Outbox);

        assertEquals(1, result.Rated().size());
        assertEquals(1L, Count(conn, "cdr"));                 // cdr + chargeable + outbox row committed together
        assertEquals(1L, Count(conn, "acc_chargeable"));
        assertEquals(1L, Count(conn, "summary_affected"));

        // the outbox blob decodes back to the rated cdr (what the summary-service consumes).
        var decoded = SummaryOutboxWriter.Decode(FirstOutboxData(conn));
        assertEquals(1, decoded.size());
        assertEquals("uid-1", decoded.get(0).Cdr().UniqueBillId);
        assertEquals(10, decoded.get(0).Customer().servicegroup);
    }

    @Test
    void Outbox_write_failure_rolls_back_the_whole_batch() {
        CreateDb(conn);
        CreatePermissive(conn, "cdr", cdr.ExtInsertColumns);
        CreatePermissive(conn, "acc_chargeable", acc_chargeable.ExtInsertColumns);
        Exec(conn, "drop table if exists summary_affected");   // missing → the outbox write (LAST in the tx) throws

        assertThrows(Exception.class, () -> MySqlCdrBatchRunner.Default().Run(conn, Mediation(), Retail5,
                List.of(Call("uid-1", LocalDateTime.of(2026, 6, 19, 14, 30, 0))),
                null, BatchSqlWriter.DefaultSegmentSize, SummaryMode.Outbox));

        assertEquals(0L, Count(conn, "cdr"));                 // cdr + chargeable rolled back with the failed outbox write
        assertEquals(0L, Count(conn, "acc_chargeable"));
    }
}
