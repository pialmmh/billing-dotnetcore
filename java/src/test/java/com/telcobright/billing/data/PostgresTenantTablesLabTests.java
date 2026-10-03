package com.telcobright.billing.data;

import com.telcobright.billing.testsupport.PostgresLab;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B7 — its tables, made by billing-core (LAB: a throwaway PostgreSQL; skipped without {@code -Dbc.lab.pg.url}).
 * The schema is made the way prime-context's provisioning makes it — the right to create, the default privileges —
 * and is EMPTY of billing-core's tables. What is proven here: they are made in one step with all their partitions;
 * their rights hold whatever the default privileges give; a month is added before it is needed and never fails a
 * batch — not even when the DEFAULT partition already holds a row of it.
 */
class PostgresTenantTablesLabTests {
    private static final String Schema = "bct_tables";

    private final AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 4));
    private Connection conn;

    @BeforeEach
    void anEmptyTenantSchema() throws SQLException {
        PostgresLab.Required();
        PostgresLab.FreshTenantSchema(Schema);
        conn = PostgresLab.Factory().Open(Schema);
        conn.setAutoCommit(false);              // as the batch runner has it when it calls Ensure
    }

    @AfterEach
    void closeAndDrop() throws SQLException {
        if (conn == null) return;
        conn.close();
        PostgresLab.DropSchema(Schema);
    }

    private PostgresTenantTables Tables() {
        return new PostgresTenantTables(PostgresTenantTables.Options.Defaults(), today::get);
    }

    private static List<String> PartitionsOf(String table) {
        return PostgresLab.Column("select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid"
                + " join pg_class p on p.oid = i.inhparent join pg_namespace n on n.oid = p.relnamespace"
                + " where n.nspname = '" + Schema + "' and p.relname = '" + table + "' order by c.relname");
    }

    private static String PartitionHolding(String table, String where) {
        return PostgresLab.Scalar("select tableoid::regclass::text from " + Schema + "." + table + " where " + where);
    }

    /** One row of {@code cdr} with the given start time, written as billing-core would (its own role). */
    private static void ACdrRow(long idCall, String startTime) {
        assertNull(PostgresLab.RefusalOf(PostgresLab.BillingRole, Schema, "insert into cdr (SwitchId, IdCall, SequenceNumber, ServiceGroup,"
                + " StartTime, ChannelCallUuid) values (1, " + idCall + ", " + idCall + ", 30, '" + startTime + "', 'u-" + idCall + "')"));
    }

    // ── made in one step ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void an_empty_schema_gets_the_four_tables_each_with_all_its_partitions() {
        Tables().Ensure(conn, Schema);

        assertEquals(List.of("acc_chargeable", "cdr", "cdrerror", "summary_affected"), PostgresLab.Column(
                "select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = '" + Schema
                        + "' and c.relkind in ('r', 'p') and not c.relispartition order by 1"));
        List<String> monthsAndDefault = List.of("p202609", "p202610", "p202611", "p202612", "p202701", "pdefault");
        for (String table : List.of("cdr", "cdrerror", "acc_chargeable"))
            assertEquals(monthsAndDefault.stream().map(p -> table + "_" + p).toList(), PartitionsOf(table), table);
        assertEquals("p", PostgresLab.Scalar("select relkind from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = '" + Schema + "' and relname = 'cdr'"), "cdr is a partitioned table");
        assertEquals("r", PostgresLab.Scalar("select relkind from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = '" + Schema + "' and relname = 'summary_affected'"), "the outbox is not");
        assertEquals(PostgresLab.BillingRole, PostgresLab.Scalar("select tableowner from pg_tables where schemaname = '" + Schema
                + "' and tablename = 'summary_affected'"));
    }

    @Test
    void the_tables_have_their_indexes_and_the_outbox_hands_its_ids_out_one_at_a_time() {
        Tables().Ensure(conn, Schema);

        List<String> indexes = PostgresLab.Column("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = '" + Schema + "' and c.relkind = 'I' order by 1");
        assertEquals(List.of("acc_chargeable_pkey", "ix_acc_chargeable_idevent", "ix_acc_chargeable_uniquebillid", "ix_cdr_idcall",
                "ix_cdr_servicegroup_starttime", "ix_cdr_uniquebillid", "ix_cdrerror_idcall", "ix_cdrerror_uniquebillid",
                "ux_cdr_call", "ux_cdrerror_call"), indexes);
        assertEquals("1", PostgresLab.Scalar("select cache_size from pg_sequences where schemaname = '" + Schema + "'"),
                "CACHE 1: no session holds a block of outbox ids, so they are seen in commit order");
        assertEquals("110", PostgresLab.Scalar("select count(*) from information_schema.columns where table_schema = '" + Schema
                + "' and table_name = 'cdr'"));
        assertEquals("110", PostgresLab.Scalar("select count(*) from information_schema.columns where table_schema = '" + Schema
                + "' and table_name = 'cdrerror'"));
    }

    @Test
    void the_unique_key_of_a_call_refuses_a_second_row_of_it() {
        Tables().Ensure(conn, Schema);
        ACdrRow(1, "2026-10-02 21:14:03");

        String refusal = PostgresLab.RefusalOf(PostgresLab.BillingRole, Schema, "insert into cdr (SwitchId, IdCall, SequenceNumber,"
                + " ServiceGroup, StartTime, ChannelCallUuid) values (1, 2, 2, 30, '2026-10-02 21:14:03', 'u-1')");

        assertNotNull(refusal, "the hard backstop of the idempotency");
        assertTrue(refusal.contains("ux_cdr_call") || refusal.contains("duplicate key"), refusal);
    }

    @Test
    void making_them_twice_changes_nothing_and_a_second_process_finds_them_made() {
        Tables().Ensure(conn, Schema);
        ACdrRow(1, "2026-10-02 21:14:03");

        Tables().Ensure(conn, Schema);                      // another billing-core process: its own cache is empty

        assertEquals(1L, PostgresLab.Count(Schema + ".cdr"));
        assertEquals(6, PartitionsOf("cdr").size());
    }

    @Test
    void a_missing_table_is_made_again_with_all_its_partitions_and_the_others_are_left_alone() {
        Tables().Ensure(conn, Schema);
        ACdrRow(1, "2026-10-02 21:14:03");
        PostgresLab.AsAdmin("DROP TABLE " + Schema + ".acc_chargeable");

        Tables().Ensure(conn, Schema);

        assertEquals(6, PartitionsOf("acc_chargeable").size());
        assertEquals(1L, PostgresLab.Count(Schema + ".cdr"), "cdr was there: not touched");
    }

    // ── the rights ───────────────────────────────────────────────────────────────────────────────────────────

    private void TheSummaryServiceMayDeleteFromTheOutboxOnly() {
        Tables().Ensure(conn, Schema);
        ACdrRow(1, "2026-10-02 21:14:03");
        assertNull(PostgresLab.RefusalOf(PostgresLab.BillingRole, Schema, "insert into summary_affected (entity_type, op, data) values ('cdr', 'add', 'x')"));

        String summary = PostgresLab.SummaryRole;
        assertNull(PostgresLab.RefusalOf(summary, Schema, "select count(*) from cdr"), "it reads cdr");
        assertNull(PostgresLab.RefusalOf(summary, Schema, "select count(*) from cdrerror"));
        assertNull(PostgresLab.RefusalOf(summary, Schema, "select count(*) from acc_chargeable"));
        assertNull(PostgresLab.RefusalOf(summary, Schema, "select id, op, data from summary_affected where entity_type = 'cdr' and id > 0"));
        assertNull(PostgresLab.RefusalOf(summary, Schema, "delete from summary_affected where entity_type = 'cdr' and id <= 1"),
                "it deletes what it has consumed from the outbox");
        assertEquals(0L, PostgresLab.Count(Schema + ".summary_affected"));

        for (String table : List.of("cdr", "cdrerror", "acc_chargeable")) {
            String refusal = PostgresLab.RefusalOf(summary, Schema, "delete from " + table);
            assertNotNull(refusal, "it must NOT delete from " + table);
            assertTrue(refusal.contains("permission denied for table " + table), refusal);
        }
        for (String partition : List.of("cdr_p202610", "cdr_pdefault", "cdrerror_p202610", "acc_chargeable_p202610")) {
            String refusal = PostgresLab.RefusalOf(summary, Schema, "delete from " + partition);
            assertNotNull(refusal, "a partition is a table: it must NOT delete from " + partition);
            assertTrue(refusal.contains("permission denied for table " + partition), refusal);
        }
        assertNotNull(PostgresLab.RefusalOf(summary, Schema, "insert into summary_affected (entity_type, op, data) values ('cdr', 'add', 'y')"));
        assertNotNull(PostgresLab.RefusalOf(summary, Schema, "update cdr set durationsec = 0"));
        assertEquals(1L, PostgresLab.Count(Schema + ".cdr"), "the record of the call is still there");
    }

    @Test
    void the_summary_service_deletes_from_the_outbox_and_cannot_delete_from_cdr_with_todays_default_privileges() {
        // prime-context main 1a2e35c: ALTER DEFAULT PRIVILEGES … GRANT SELECT, DELETE ON TABLES TO summary_service.
        // Without billing-core's own statement that would let the summary service delete from cdr and from each partition.
        TheSummaryServiceMayDeleteFromTheOutboxOnly();
    }

    @Test
    void the_same_holds_once_the_default_privileges_give_select_only() throws SQLException {
        conn.close();
        PostgresLab.FreshTenantSchema(Schema, PostgresLab.SummaryOnBillingAfterW12);     // prime-context after its W12
        conn = PostgresLab.Factory().Open(Schema);
        conn.setAutoCommit(false);

        TheSummaryServiceMayDeleteFromTheOutboxOnly();
    }

    @Test
    void the_reader_role_reads_the_four_tables_and_writes_none() {
        Tables().Ensure(conn, Schema);
        ACdrRow(1, "2026-10-02 21:14:03");

        String reader = PostgresLab.ReaderRole;
        for (String table : List.of("cdr", "cdrerror", "acc_chargeable", "summary_affected"))
            assertNull(PostgresLab.RefusalOf(reader, Schema, "select count(*) from " + table), table);
        assertNull(PostgresLab.RefusalOf(reader, Schema, "select IdCall, ChannelCallUuid, InPartnerId from cdr where ServiceGroup = 30"
                + " and StartTime >= '2026-10-01' order by StartTime desc, IdCall desc limit 20"), "the report road's query");
        assertNotNull(PostgresLab.RefusalOf(reader, Schema, "delete from cdr"));
        assertNotNull(PostgresLab.RefusalOf(reader, Schema, "delete from summary_affected"));
    }

    @Test
    void the_rights_are_billing_cores_own_statement_they_hold_in_a_schema_with_no_default_privileges_at_all() throws SQLException {
        conn.close();
        PostgresLab.AsAdmin("DROP SCHEMA IF EXISTS " + Schema + " CASCADE", "CREATE SCHEMA " + Schema + " AUTHORIZATION prime_context",
                "GRANT USAGE, CREATE ON SCHEMA " + Schema + " TO ad_sphere, billing_core, summary_service");   // and nothing else
        conn = PostgresLab.Factory().Open(Schema);
        conn.setAutoCommit(false);

        Tables().Ensure(conn, Schema);

        for (String table : List.of("cdr", "cdrerror", "acc_chargeable", "summary_affected")) {
            assertNull(PostgresLab.RefusalOf(PostgresLab.ReaderRole, Schema, "select count(*) from " + table), "the reader reads " + table);
            assertNull(PostgresLab.RefusalOf(PostgresLab.SummaryRole, Schema, "select count(*) from " + table), "the summary service reads " + table);
        }
        assertNull(PostgresLab.RefusalOf(PostgresLab.SummaryRole, Schema, "delete from summary_affected"));
        assertNotNull(PostgresLab.RefusalOf(PostgresLab.SummaryRole, Schema, "delete from cdr"));
    }

    @Test
    void a_named_role_that_does_not_exist_stops_the_schema_and_makes_nothing() {
        var tables = new PostgresTenantTables(new PostgresTenantTables.Options("bct_no_such_role", List.of("ad_sphere"), 1, 3), today::get);

        IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> tables.Ensure(conn, Schema));

        assertTrue(stopped.getMessage().contains("bct_no_such_role"), "by name: " + stopped.getMessage());
        assertTrue(stopped.getMessage().contains("schema " + Schema), stopped.getMessage());
        assertEquals("0", PostgresLab.Scalar("select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where n.nspname = '" + Schema + "'"), "one step: nothing of it is left behind");
    }

    // ── the months ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void a_row_goes_to_its_months_partition_and_a_row_of_no_month_to_the_default_one() {
        Tables().Ensure(conn, Schema);

        ACdrRow(1, "2026-10-02 21:14:03");
        ACdrRow(2, "2026-09-30 23:59:59");
        ACdrRow(3, "2027-01-31 23:59:59");
        ACdrRow(4, "2031-05-05 05:05:05");          // a clock gone wrong: still stored
        ACdrRow(5, "0001-01-01 00:00:00");          // the model's "no time"

        assertEquals(Schema + ".cdr_p202610", PartitionHolding("cdr", "idcall = 1"));
        assertEquals(Schema + ".cdr_p202609", PartitionHolding("cdr", "idcall = 2"));
        assertEquals(Schema + ".cdr_p202701", PartitionHolding("cdr", "idcall = 3"));
        assertEquals(Schema + ".cdr_pdefault", PartitionHolding("cdr", "idcall = 4"));
        assertEquals(Schema + ".cdr_pdefault", PartitionHolding("cdr", "idcall = 5"));
    }

    @Test
    void the_months_ahead_are_added_as_time_goes_by_once_a_day() {
        PostgresTenantTables tables = Tables();
        tables.Ensure(conn, Schema);                         // 2026-10-04: … 202701
        assertEquals("cdr_p202701", Last(PartitionsOf("cdr")));

        today.set(LocalDate.of(2026, 10, 31));
        tables.Ensure(conn, Schema);                         // the same month: nothing to add
        assertEquals("cdr_p202701", Last(PartitionsOf("cdr")));

        today.set(LocalDate.of(2026, 11, 1));
        tables.Ensure(conn, Schema);                         // November: three ahead = 202702

        for (String table : List.of("cdr", "cdrerror", "acc_chargeable"))
            assertEquals(table + "_p202702", Last(PartitionsOf(table)), table);
        assertEquals(List.of("ix_cdr_idcall", "ix_cdr_servicegroup_starttime", "ix_cdr_uniquebillid", "ux_cdr_call").size(),
                Integer.parseInt(PostgresLab.Scalar("select count(*) from pg_indexes where schemaname = '" + Schema
                        + "' and tablename = 'cdr_p202702'")), "the added month has the table's indexes");
        ACdrRow(1, "2027-02-14 10:00:00");
        assertEquals(Schema + ".cdr_p202702", PartitionHolding("cdr", "idcall = 1"));
    }

    private static String Last(List<String> partitions) {
        List<String> months = partitions.stream().filter(p -> !p.endsWith("_pdefault")).toList();
        return months.get(months.size() - 1);
    }

    @Test
    void a_month_whose_rows_already_sit_in_the_default_partition_is_still_added_and_takes_them() {
        // THE TRAP: PostgreSQL refuses to create a partition while the DEFAULT partition holds a row of its range.
        PostgresTenantTables tables = Tables();
        tables.Ensure(conn, Schema);
        ACdrRow(1, "2027-02-14 10:00:00");                   // dated in a month that has no partition yet
        ACdrRow(2, "2031-05-05 05:05:05");                   // and one that stays out of every month
        assertEquals(Schema + ".cdr_pdefault", PartitionHolding("cdr", "idcall = 1"));
        assertNotNull(PostgresLab.RefusalOf(PostgresLab.BillingRole, Schema,
                "create table cdr_p202702 partition of cdr for values from ('2027-02-01 00:00:00') to ('2027-03-01 00:00:00')"),
                "the plain way is refused: the default partition holds a row of February 2027");

        today.set(LocalDate.of(2026, 11, 1));                // February 2027 comes into the window
        List<String> notAdded = tables.Ensure(conn, Schema);

        assertEquals(List.of(), notAdded);
        assertEquals(Schema + ".cdr_p202702", PartitionHolding("cdr", "idcall = 1"), "the row moved into its month");
        assertEquals(Schema + ".cdr_pdefault", PartitionHolding("cdr", "idcall = 2"));
        assertEquals(2L, PostgresLab.Count(Schema + ".cdr"), "nothing lost, nothing doubled");
        assertEquals("u-1", PostgresLab.Scalar("select channelcalluuid from " + Schema + ".cdr where idcall = 1"));
        String refusal = PostgresLab.RefusalOf(PostgresLab.SummaryRole, Schema, "delete from cdr_p202702");
        assertNotNull(refusal, "the added month has the partitions' rights");
    }

    @Test
    void a_month_that_cannot_be_added_never_fails_the_schema_and_the_months_after_it_are_made() {
        PostgresTenantTables tables = Tables();
        tables.Ensure(conn, Schema);
        // something of that name is in the way: February 2027 cannot be made
        assertNull(PostgresLab.RefusalOf(PostgresLab.BillingRole, Schema, "create table cdr_p202702 (note text)"));

        today.set(LocalDate.of(2026, 12, 1));                // the window now reaches March 2027
        List<String> notAdded = tables.Ensure(conn, Schema); // must not throw

        assertEquals(List.of("cdr_p202702"), notAdded, "it says which month it could not add (and logs an ERROR for it)");
        List<String> cdrPartitions = PartitionsOf("cdr");
        assertTrue(!cdrPartitions.contains("cdr_p202702"), "February could not be added: " + cdrPartitions);
        assertTrue(cdrPartitions.contains("cdr_p202703"), "March was: " + cdrPartitions);
        assertTrue(PartitionsOf("cdrerror").contains("cdrerror_p202702"), "and the other tables' February too");
        ACdrRow(1, "2027-02-14 10:00:00");
        assertEquals(Schema + ".cdr_pdefault", PartitionHolding("cdr", "idcall = 1"), "its rows go to the default partition meanwhile");
    }

    // ── a table that is not ours ─────────────────────────────────────────────────────────────────────────────

    @Test
    void a_cdr_table_somebody_else_made_is_refused_in_words_and_never_altered() {
        // the shape ad-sphere's PC stand-in makes
        PostgresLab.AsAdmin("CREATE TABLE " + Schema + ".cdr (IdCall BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + " SequenceNumber BIGINT, ServiceGroup INT NOT NULL, UniqueBillId VARCHAR(100), ChannelCallUuid VARCHAR(100) NOT NULL,"
                + " StartTime TIMESTAMP NOT NULL)");

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> Tables().Ensure(conn, Schema));

        assertTrue(refused.getMessage().contains("already has a table 'cdr' that is not billing-core's"), refused.getMessage());
        assertTrue(refused.getMessage().contains("switchid"), "it names what is missing: " + refused.getMessage());
        assertEquals("6", PostgresLab.Scalar("select count(*) from information_schema.columns where table_schema = '" + Schema
                + "' and table_name = 'cdr'"), "not altered");
    }
}
