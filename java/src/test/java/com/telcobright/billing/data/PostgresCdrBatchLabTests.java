package com.telcobright.billing.data;

import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.sql.SqlDialect;
import com.telcobright.billing.testsupport.PostgresLab;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 — PostgreSQL as a write target (LAB: a throwaway PostgreSQL; skipped without {@code -Dbc.lab.pg.url}). The same
 * pipeline and the same single transaction as on MySQL ({@code CdrBatchAtomicityTests}); what changes is the edge:
 * the connection (a tenant is a schema), the per-tenant batch lock (an advisory lock held across the commit), the
 * literals the writers emit, the id seed.
 */
class PostgresCdrBatchLabTests {
    private static final String Schema = "bct_batch";
    private static final String OtherSchema = "bct_batch_other";
    private static final LocalDateTime When = LocalDateTime.of(2026, 6, 19, 14, 30, 0);

    private Connection conn;

    @BeforeEach
    void aFreshTenantSchema() {
        PostgresLab.Required();
        PostgresLab.FreshTenantSchema(Schema);
        conn = PostgresLab.Factory().Open(Schema);
    }

    @AfterEach
    void closeAndDrop() throws SQLException {
        if (conn == null) return;
        conn.close();
        PostgresLab.DropSchema(Schema);
        PostgresLab.DropSchema(OtherSchema);
    }

    private static PostgresEdge Edge() {
        return new PostgresEdge(new PostgresTenantTables(PostgresTenantTables.Options.Defaults()));
    }

    private static MySqlCdrBatchRunner Runner() {
        return MySqlCdrBatchRunner.On(Edge());
    }

    private static final Map<Integer, Partner> Retail5 = Map.of(5, new Partner(5, null, 3));

    private static MediationContext Mediation() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, TestData.Ra(8801712, "1.0").idRatePlan(7));
        return f.mediation();
    }

    /** The same rate-able (SG10) call as the MySQL batch test's. */
    private static cdr Call(String billId, LocalDateTime when) {
        cdr c = new cdr();
        c.SwitchId = 1;
        c.InPartnerId = 5;
        c.IncomingRoute = "in";
        c.OutgoingRoute = "out";
        c.OriginatingIP = "1.1.1.1";
        c.TerminatingIP = "2.2.2.2";
        c.TerminatingCalledNumber = "8801712345678"; c.OriginatingCalledNumber = "8801712345678";
        c.OriginatingCallingNumber = "8801999000111";
        c.StartTime = when;
        c.AnswerTime = when;
        c.EndTime = when.plusSeconds(60);
        c.SignalingStartTime = when;
        c.ChargingStatus = 1;
        c.DurationSec = BigDecimal.valueOf(60);
        c.CountryCode = "880";
        c.UniqueBillId = billId;
        c.Category = 1;
        c.SubCategory = 1;
        return c;
    }

    private static long Rows(String table) {
        return PostgresLab.Count(Schema + "." + table);
    }

    // ── the same batch test as on MySQL ──────────────────────────────────────────────────────────────────────

    @Test
    void Batch_commits_atomically_on_success() {
        var result = Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When)));

        assertEquals(1, result.Rated().size());
        assertEquals(1L, Rows("cdr"));                 // cdr + chargeable + outbox row committed together
        assertEquals(1L, Rows("acc_chargeable"));
        assertEquals(1L, Rows("summary_affected"));

        // the outbox blob decodes back to the rated cdr (what the summary-service consumes).
        var decoded = SummaryOutboxWriter.Decode(PostgresLab.Scalar("select data from " + Schema + ".summary_affected order by id limit 1"));
        assertEquals(1, decoded.size());
        assertEquals("uid-1", decoded.get(0).Cdr().UniqueBillId);
        assertEquals(10, decoded.get(0).Customer().servicegroup);
        assertEquals("1.00000000", PostgresLab.Scalar("select billedamount::text from " + Schema + ".acc_chargeable"));
    }

    @Test
    void Batch_rolls_back_entirely_on_a_mid_batch_failure() {
        Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-0", When)));           // the tables are made
        PostgresLab.AsAdmin("ALTER TABLE " + Schema + ".acc_chargeable ADD CONSTRAINT bct_no_more CHECK (id < 2)");

        // the cdr row writes first, then the chargeable write fails → the top-level runner rolls back the batch.
        assertThrows(Exception.class, () -> Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When))));

        assertEquals(1L, Rows("cdr"));                 // only the first batch's row: the already-written cdr row was rolled back
        assertEquals(1L, Rows("summary_affected"));    // nothing else persisted either
    }

    @Test
    void Outbox_write_failure_rolls_back_the_whole_batch() {
        Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-0", When)));
        PostgresLab.AsAdmin("ALTER TABLE " + Schema + ".summary_affected ADD CONSTRAINT bct_no_more CHECK (id < 2)");

        assertThrows(Exception.class, () -> Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When))));

        assertEquals(1L, Rows("cdr"));                 // cdr + chargeable rolled back with the failed outbox write
        assertEquals(1L, Rows("acc_chargeable"));
    }

    @Test
    void the_connection_is_usable_again_after_a_batch_that_rolled_back() {
        Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-0", When)));
        PostgresLab.AsAdmin("ALTER TABLE " + Schema + ".summary_affected ADD CONSTRAINT bct_no_more CHECK (id < 2)");
        assertThrows(Exception.class, () -> Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When))));
        PostgresLab.AsAdmin("ALTER TABLE " + Schema + ".summary_affected DROP CONSTRAINT bct_no_more");

        var again = Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When)));   // the lock was released, the tx is clean

        assertEquals(1, again.Rated().size());
        assertEquals(2L, Rows("cdr"));
    }

    // ── the literals ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_quote_a_backslash_and_a_nul_in_a_string_do_not_break_or_change_the_row() {
        cdr c = Call("uid-1", When);
        c.IncomingRoute = "O'Brien\\north";                    // one quote, one backslash
        c.OriginatingCallingNumber = "aa\u0000bb";             // a NUL: PostgreSQL refuses it in text
        c.AdditionalMetaData = "{\"path\":\"C:\\\\ads\\\\x\",\"q\":\"it's\"}";
        c.UniqueBillId = "bill\\1";                            // it travels to the chargeable's row too

        Runner().Run(conn, Mediation(), Retail5, List.of(c));

        assertEquals("bill\\1", PostgresLab.Scalar("select uniquebillid from " + Schema + ".acc_chargeable"),
                "the chargeable writer emits PostgreSQL's literals too");

        assertEquals("O'Brien\\north", PostgresLab.Scalar("select incomingroute from " + Schema + ".cdr"),
                "the backslash is stored once, not doubled");
        assertEquals("aabb", PostgresLab.Scalar("select originatingcallingnumber from " + Schema + ".cdr"));
        assertEquals("{\"path\":\"C:\\\\ads\\\\x\",\"q\":\"it's\"}", PostgresLab.Scalar("select additionalmetadata from " + Schema + ".cdr"),
                "character for character");
    }

    @Test
    void the_session_reads_a_string_the_standard_way_whatever_the_roles_default_is() throws SQLException {
        // A server (or a role) may still default to the pre-9.1 reading, where a backslash in '…' is an escape.
        PostgresLab.AsAdmin("ALTER ROLE " + PostgresLab.BillingRole + " IN DATABASE " + PostgresLab.Database()
                + " SET standard_conforming_strings = off");
        try (Connection withThatDefault = PostgresLab.Factory().Open(Schema)) {
            cdr c = Call("uid-1", When);
            c.IncomingRoute = "north\\tower's";

            Runner().Run(withThatDefault, Mediation(), Retail5, List.of(c));
        } finally {
            PostgresLab.AsAdmin("ALTER ROLE " + PostgresLab.BillingRole + " IN DATABASE " + PostgresLab.Database()
                    + " RESET standard_conforming_strings");
        }

        assertEquals("north\\tower's", PostgresLab.Scalar("select incomingroute from " + Schema + ".cdr"));
    }

    @Test
    void a_time_is_read_back_as_the_wall_clock_it_holds_even_one_the_jvms_zone_does_not_have() throws SQLException {
        Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", LocalDateTime.of(2026, 3, 8, 2, 30, 0))));
        TimeZone jvm = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));       // 2026-03-08 02:30 does not exist there
            try (var st = conn.createStatement();
                 var rs = st.executeQuery("select " + CdrRowMapper.SelectColumns(SqlDialect.PostgreSql) + " from cdr")) {
                rs.next();
                cdr read = CdrRowMapper.FromResultSet(rs, SqlDialect.PostgreSql);

                assertEquals(LocalDateTime.of(2026, 3, 8, 2, 30, 0), read.StartTime, "no zone takes part in the read");
                assertEquals("uid-1", read.ChannelCallUuid, "and the wire's six columns come back with the row");
            }
        } finally {
            TimeZone.setDefault(jvm);
        }
    }

    @Test
    void a_record_that_came_without_a_call_uuid_takes_its_bill_id_as_one_and_is_idempotent() {
        Runner().Run(conn, Mediation(), Retail5, List.of(Call("session-77", When)));          // as the gRPC entries build it

        var again = Runner().Run(conn, Mediation(), Retail5, List.of(Call("session-77", When)));

        assertEquals(0, again.Rated().size());
        assertEquals(1L, Rows("cdr"));
        assertEquals("session-77", PostgresLab.Scalar("select channelcalluuid from " + Schema + ".cdr"));
    }

    @Test
    void a_table_that_went_missing_is_made_again_after_the_batch_that_found_it_gone() {
        MySqlCdrBatchRunner runner = Runner();                 // ONE process: it remembers that the schema was ready today
        runner.Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When)));
        PostgresLab.AsAdmin("DROP TABLE " + Schema + ".acc_chargeable");

        assertThrows(Exception.class, () -> runner.Run(conn, Mediation(), Retail5, List.of(Call("uid-2", When))));
        var third = runner.Run(conn, Mediation(), Retail5, List.of(Call("uid-2", When)));    // it looked again

        assertEquals(1, third.Rated().size());
        assertEquals(2L, Rows("cdr"));
        assertEquals(1L, Rows("acc_chargeable"), "the new table holds the second call's chargeable");
    }

    @Test
    void a_time_is_stored_as_the_wall_clock_it_came_with_whatever_zone_the_jvm_runs_in() {
        TimeZone jvm = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));       // far from Dhaka and from the server's UTC
            Connection elsewhere = PostgresLab.Factory().Open(Schema);
            cdr c = Call("uid-1", LocalDateTime.of(2026, 10, 2, 21, 14, 3));

            Runner().Run(elsewhere, Mediation(), Retail5, List.of(c));
            elsewhere.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            TimeZone.setDefault(jvm);
        }

        assertEquals("2026-10-02 21:14:03", PostgresLab.Scalar("select starttime::text from " + Schema + ".cdr"));
        assertEquals("2026-10-02 21:15:03", PostgresLab.Scalar("select endtime::text from " + Schema + ".cdr"));
        assertEquals("2026-10-02 21:14:03", PostgresLab.Scalar("select transactiontime::text from " + Schema + ".acc_chargeable"));
        assertEquals("timestamp without time zone", PostgresLab.Scalar("select data_type from information_schema.columns"
                + " where table_schema = '" + Schema + "' and table_name = 'cdr' and column_name = 'starttime'"));
    }

    @Test
    void on_postgresql_the_wires_six_columns_are_written() {
        cdr c = Call("uid-1", When);
        c.ResellerHierarchy = "bct_root > " + Schema;
        c.ChannelCallUuid = "7def7167-dad1-4215-8680-a3e0d24d1b6a";
        c.HangupCause = "NORMAL_CLEARING";
        c.InPartnerUom = "TF_min";
        c.IdPackageAccount = 236L;
        c.PackageAmount = new BigDecimal("1.5");

        Runner().Run(conn, Mediation(), Retail5, List.of(c));

        assertEquals("bct_root > " + Schema + "|7def7167-dad1-4215-8680-a3e0d24d1b6a|NORMAL_CLEARING|TF_min|236|1.50000000",
                PostgresLab.Scalar("select resellerhierarchy || '|' || channelcalluuid || '|' || hangupcause || '|' || inpartneruom"
                        + " || '|' || idpackageaccount || '|' || packageamount from " + Schema + ".cdr"));
    }

    // ── the per-tenant batch lock ────────────────────────────────────────────────────────────────────────────

    @Test
    void the_lock_is_the_tenants_not_the_databases() throws SQLException {
        PostgresLab.FreshTenantSchema(OtherSchema);
        PostgresEdge edge = Edge();
        try (Connection other = PostgresLab.Factory().Open(OtherSchema)) {
            assertEquals(Schema, edge.SchemaOf(conn), "the schema, not the catalog: every tenant shares the database");
            assertEquals(OtherSchema, edge.SchemaOf(other));
            assertEquals(conn.getCatalog(), other.getCatalog());
            assertNotEquals(PostgresEdge.LockKeyOf("billing_batch_" + Schema), PostgresEdge.LockKeyOf("billing_batch_" + OtherSchema));

            edge.AcquireBatchLock(conn, "billing_batch_" + Schema);
            try {
                assertEquals("f", PostgresLab.Scalar("select pg_try_advisory_lock(" + PostgresEdge.LockKeyOf("billing_batch_" + Schema) + ")"),
                        "another session cannot take the same tenant's lock");
                var result = Runner().Run(other, Mediation(), Retail5, List.of(Call("uid-other", When)));
                assertEquals(1, result.Rated().size(), "another tenant's batch is not queued behind it");
            } finally {
                edge.ReleaseBatchLock(conn, "billing_batch_" + Schema);
            }
        }
    }

    @Test
    void a_batch_waits_for_its_tenants_lock_only_so_long_and_the_connection_stays_usable() throws SQLException {
        PostgresEdge patient = Edge();
        PostgresEdge impatient = new PostgresEdge(new PostgresTenantTables(PostgresTenantTables.Options.Defaults()), 1);
        try (Connection busy = PostgresLab.Factory().Open(Schema)) {
            busy.setAutoCommit(false);
            patient.AcquireBatchLock(busy, "billing_batch_" + Schema);          // another batch of this tenant is running
            conn.setAutoCommit(false);

            RuntimeException gaveUp = assertThrows(RuntimeException.class, () -> impatient.AcquireBatchLock(conn, "billing_batch_" + Schema));

            assertTrue(gaveUp.getMessage().contains("billing_batch_" + Schema + " not acquired within 1s"), gaveUp.getMessage());
            patient.ReleaseBatchLock(busy, "billing_batch_" + Schema);
            busy.commit();
        }
        conn.setAutoCommit(true);

        var result = Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When)));   // the same connection, afterwards

        assertEquals(1, result.Rated().size());
        try (var st = conn.createStatement(); var rs = st.executeQuery("show lock_timeout")) {
            rs.next();
            assertEquals("0", rs.getString(1), "the timeout was the lock statement's only");
        }
    }

    /** The edge of a real batch, watched from a second session at the two moments that matter. */
    private static final class WatchedEdge implements DatasourceEdge {
        final PostgresEdge real = Edge();
        String lockHeldWhileWriting, rowsVisibleAtRelease;

        @Override public SqlDialect Dialect() { return real.Dialect(); }
        @Override public String SchemaOf(Connection conn) { return real.SchemaOf(conn); }
        @Override public void AcquireBatchLock(Connection conn, String name) { real.AcquireBatchLock(conn, name); }
        @Override public void PrepareSchema(Connection conn, String schema) { real.PrepareSchema(conn, schema); }
        @Override public IdempotencyKey Key() { return real.Key(); }

        @Override
        public ISqlExecutor Executor(Connection conn) {
            ISqlExecutor writes = real.Executor(conn);
            return new ISqlExecutor() {
                @Override public int ExecuteNonQuery(String sql) {
                    if (lockHeldWhileWriting == null)
                        lockHeldWhileWriting = PostgresLab.Scalar("select not pg_try_advisory_lock("
                                + PostgresEdge.LockKeyOf("billing_batch_" + Schema) + ")");
                    return writes.ExecuteNonQuery(sql);
                }
                @Override public SqlDialect Dialect() { return writes.Dialect(); }
            };
        }

        @Override
        public void ReleaseBatchLock(Connection conn, String name) {
            rowsVisibleAtRelease = PostgresLab.Scalar("select count(*) from " + Schema + ".summary_affected");
            real.ReleaseBatchLock(conn, name);
        }
    }

    @Test
    void the_lock_is_taken_before_the_first_insert_and_released_only_after_the_commit() {
        WatchedEdge watched = new WatchedEdge();

        MySqlCdrBatchRunner.On(watched).Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When)));

        assertEquals("t", watched.lockHeldWhileWriting, "held while the batch writes");
        assertEquals("1", watched.rowsVisibleAtRelease, "at the release the outbox row is already committed: ids are seen in commit order");
        assertEquals("t", PostgresLab.Scalar("select pg_try_advisory_lock(" + PostgresEdge.LockKeyOf("billing_batch_" + Schema)
                + ")"), "and it is free afterwards");
    }

    // ── the id seed ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void the_ids_go_on_from_the_highest_one_in_cdr_and_cdrerror() {
        cdr unrated = Call("uid-err", When);
        unrated.OriginatingCalledNumber = "8809999999";            // no rate for it: RATE_NOT_FOUND -> cdrerror
        unrated.TerminatingCalledNumber = "8809999999";

        Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-1", When), unrated));
        Runner().Run(conn, Mediation(), Retail5, List.of(Call("uid-2", When)));

        assertEquals(List.of("1", "3"), PostgresLab.Column("select idcall from " + Schema + ".cdr order by idcall"));
        assertEquals(List.of("2"), PostgresLab.Column("select idcall from " + Schema + ".cdrerror"));
        assertEquals(List.of("1", "2"), PostgresLab.Column("select id from " + Schema + ".acc_chargeable order by id"));
        assertEquals(List.of("1", "3"), PostgresLab.Column("select idevent from " + Schema + ".acc_chargeable order by id"),
                "a chargeable points at its cdr by IdCall");
    }

    // ── the connection ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_tenant_whose_schema_is_not_in_the_database_is_refused_in_words() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> PostgresLab.Factory().Open("bct_no_such_tenant"));

        assertTrue(refused.getMessage().contains("schema 'bct_no_such_tenant' is not in database '" + PostgresLab.Database() + "'"),
                refused.getMessage());
    }

    @Test
    void a_tenant_name_that_is_not_a_schema_name_never_reaches_the_database() {
        for (String name : new String[] {"Bct_Batch", "res-44", "btcl; drop schema btcl", "", null})
            assertThrows(IllegalArgumentException.class, () -> PostgresLab.Factory().Open(name), "'" + name + "'");
    }

    @Test
    void the_search_path_is_the_tenants_schema_and_nothing_else() throws SQLException {
        assertEquals(Schema, conn.getSchema());
        try (var st = conn.createStatement(); var rs = st.executeQuery("show search_path")) {
            rs.next();
            assertEquals(Schema, rs.getString(1), "never public: an unqualified cdr is this tenant's or nobody's");
        }
    }

    @Test
    void the_executor_of_the_postgresql_edge_says_postgresql() {
        assertEquals(SqlDialect.PostgreSql, Edge().Executor(conn).Dialect());
        assertEquals(SqlDialect.PostgreSql, PostgresLab.Factory().Dialect());
        assertEquals(IdempotencyKey.ChannelCallUuid, Edge().Key());
    }
}
