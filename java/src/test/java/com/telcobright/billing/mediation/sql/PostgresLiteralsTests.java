package com.telcobright.billing.mediation.sql;

import com.telcobright.billing.mediation.cdr.CdrRowSql;
import com.telcobright.billing.mediation.cdr.ChargeableRowSql;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 — the literals the writers emit on PostgreSQL. A number, a date and NULL are MySQL's; a string is not
 * (a backslash is itself; a NUL is dropped). And the row: the same columns in the same order as MySQL's, then the
 * ratified wire's six. What the database makes of them is {@code PostgresCdrBatchLabTests}.
 */
class PostgresLiteralsTests {

    @Test
    void a_backslash_is_left_alone_where_mysql_doubles_it() {
        assertEquals("'a\\b'", PostgresLiterals.Text("a\\b"));                       // PostgreSQL: 'a\b'
        assertEquals("'a\\\\b'", MySqlFieldExtensions.ToMySqlField("a\\b"));         // MySQL:      'a\\b'
    }

    @Test
    void a_quote_is_doubled_on_both_engines() {
        assertEquals("'O''Brien'", PostgresLiterals.Text("O'Brien"));
        assertEquals("'O''Brien'", MySqlFieldExtensions.ToMySqlField("O'Brien"));
    }

    @Test
    void a_nul_character_is_dropped_because_postgresql_refuses_it_in_text() {
        assertEquals("'ab'", PostgresLiterals.Text("a\u0000b"));
        assertEquals("null", PostgresLiterals.Text("\u0000"));       // nothing is left: the empty-string rule applies
    }

    @Test
    void an_empty_string_and_the_word_null_are_sql_null_as_the_legacy_writer_has_it() {
        for (String empty : new String[] {null, "", "null", "NULL", "Null"}) {
            assertEquals("null", PostgresLiterals.Text(empty), "'" + empty + "'");
            assertEquals("null", MySqlFieldExtensions.ToMySqlField(empty).toLowerCase(), "MySQL writes the same SQL NULL: both engines hold the same row");
        }
    }

    @Test
    void numbers_dates_and_null_are_written_as_on_mysql() {
        assertEquals("0.00000001", PostgresLiterals.Of(new BigDecimal("1E-8")));               // never scientific
        assertEquals(MySqlFieldExtensions.ToMySqlField(new BigDecimal("1E-8")), PostgresLiterals.Of(new BigDecimal("1E-8")));
        assertEquals("'2026-10-02 21:14:03'", PostgresLiterals.Of(LocalDateTime.of(2026, 10, 2, 21, 14, 3)));
        assertEquals("'0001-01-01 00:00:00'", PostgresLiterals.Of(LocalDateTime.of(1, 1, 1, 0, 0)));   // the model's "no time"
        assertEquals("30", PostgresLiterals.Of(30));
        assertEquals("1790961243000017", PostgresLiterals.Of(1790961243000017L));
        assertEquals("1.0", PostgresLiterals.Of(1.0f));
        assertEquals("1", PostgresLiterals.Of((byte) 1));
        assertEquals("null", PostgresLiterals.Of(null));
    }

    @Test
    void a_float_that_is_not_a_number_is_the_word_postgresql_knows() {
        assertEquals("'NaN'", PostgresLiterals.Of(Float.NaN));
        assertEquals("'Infinity'", PostgresLiterals.Of(Float.POSITIVE_INFINITY));
        assertEquals("'-Infinity'", PostgresLiterals.Of(Double.NEGATIVE_INFINITY));
    }

    private static cdr APlainCdr() {
        cdr c = new cdr();
        c.SwitchId = 1; c.IdCall = 77; c.SequenceNumber = 9; c.FileName = "kafka:cdr"; c.ServiceGroup = 30;
        c.IncomingRoute = "cola-eid"; c.OutgoingRoute = "dhaka-north"; c.OriginatingCalledNumber = "7001";
        c.DurationSec = new BigDecimal("10"); c.PDD = 1.0f; c.ChargingStatus = 1; c.InPartnerId = 1;
        c.StartTime = LocalDateTime.of(2026, 10, 2, 21, 14, 3); c.AnswerTime = LocalDateTime.of(2026, 10, 2, 21, 14, 4);
        c.EndTime = LocalDateTime.of(2026, 10, 2, 21, 14, 14); c.SignalingStartTime = c.StartTime;
        c.CustomerRate = new BigDecimal("0.50"); c.InPartnerCost = new BigDecimal("0.50"); c.UniqueBillId = "view-1";
        c.AdditionalMetaData = "{\"campaignId\":12}"; c.FinalRecord = 5L;
        c.ResellerHierarchy = "btcl > res_44"; c.ChannelCallUuid = "view-1"; c.HangupCause = "NORMAL_CLEARING";
        c.InPartnerUom = "BDT"; c.IdPackageAccount = 3061L; c.PackageAmount = new BigDecimal("0");
        return c;
    }

    @Test
    void the_postgresql_cdr_row_is_the_mysql_row_then_the_wires_six_columns() {
        cdr c = APlainCdr();          // no backslash, no NUL: the two engines' literals are the same text

        String mysql = CdrRowSql.Values(c, SqlDialect.MySql).toString();
        String postgres = CdrRowSql.Values(c, SqlDialect.PostgreSql).toString();

        assertEquals(c.GetExtInsertValues().toString(), mysql, "MySQL's tuple is the legacy model's own, untouched");
        String legacyPart = mysql.substring(0, mysql.length() - 1);                // without the closing paren
        assertTrue(postgres.startsWith(legacyPart + ","), "the first 104 values are MySQL's, in MySQL's order");
        assertEquals(",'btcl > res_44','view-1','NORMAL_CLEARING','BDT',3061,0)", postgres.substring(legacyPart.length()));
        assertEquals(cdr.ExtInsertColumns, CdrRowSql.Columns(SqlDialect.MySql));
        assertEquals(cdr.ExtInsertColumns + ",ResellerHierarchy,ChannelCallUuid,HangupCause,InPartnerUom,IdPackageAccount,PackageAmount",
                CdrRowSql.Columns(SqlDialect.PostgreSql));
        assertEquals(110, CdrRowSql.Columns(SqlDialect.PostgreSql).split(",").length);
    }

    @Test
    void the_postgresql_chargeable_row_is_the_mysql_row_with_postgresql_strings() {
        acc_chargeable plain = new acc_chargeable();
        plain.id = 5; plain.uniqueBillId = "view-1"; plain.idEvent = 77; plain.assignedDirection = (byte) 1;
        plain.transactionTime = LocalDateTime.of(2026, 10, 2, 21, 14, 3); plain.servicegroup = 30; plain.servicefamily = 30;
        plain.BilledAmount = new BigDecimal("0.50"); plain.idBilledUom = "BDT"; plain.Prefix = "70";

        assertEquals(plain.GetExtInsertValues().toString(), ChargeableRowSql.Values(plain, SqlDialect.MySql).toString());
        assertEquals(plain.GetExtInsertValues().toString(), ChargeableRowSql.Values(plain, SqlDialect.PostgreSql).toString(),
                "no backslash in it: the same text on both engines");

        plain.description = "a\\b";
        assertTrue(ChargeableRowSql.Values(plain, SqlDialect.PostgreSql).toString().contains(",'a\\b',"));
        assertTrue(ChargeableRowSql.Values(plain, SqlDialect.MySql).toString().contains(",'a\\\\b',"));
    }

    @Test
    void a_row_builder_for_a_column_the_class_does_not_have_fails_at_once() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PostgresRow<>(cdr.class, "IdCall,NoSuchColumn"));

        assertTrue(e.getMessage().contains("NoSuchColumn"), e.getMessage());
    }

    @Test
    void an_executor_is_mysqls_unless_it_says_otherwise() {
        ISqlExecutor legacy = sql -> 1;

        assertEquals(SqlDialect.MySql, legacy.Dialect());
    }
}
