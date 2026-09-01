// Faithful port of tests/Billing.Tests/SummaryWriterTests.cs (xUnit -> JUnit 5).
// C# imported only `MediationModel` and exercises sum_voice_day_03 / AbstractCdrSummary, both of which map
// to com.telcobright.billing.mediation.engine.models — so the test lives here (RULE T0).
package com.telcobright.billing.mediation.engine.models;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/**
 * The verbatim-ported AbstractCdrSummary SQL writers (GetExtInsertValues / GetUpdateCommand): numbers as-is,
 * dates/strings quoted, null strings -> '', the table-name placeholder for substitution.
 */
class SummaryWriterTests {

    @Test
    void Insert_values_render_numbers_dates_and_strings() {
        var s = new sum_voice_day_03();
        s.id = 5;
        s.tup_switchid = 1;
        s.tup_starttime = LocalDateTime.of(2026, 6, 19, 14, 0, 0);
        s.customercost = new BigDecimal("1.5");
        s.totalcalls = 2;
        s.tup_countryorareacode = "880";
        // tup_* string fields left null -> '' via ToNotNullSqlField (no NRE)

        var insert = s.GetExtInsertValues().toString();
        assertTrue(insert.startsWith("(5,1,"));                  // id, switchid
        assertTrue(insert.contains("'2026-06-19 14:00:00'"));    // tup_starttime quoted
        assertTrue(insert.contains("'880'"));                    // country code quoted
        assertTrue(insert.contains("1.5"));                      // customercost
        assertTrue(insert.contains("''"));                       // a null string field -> ''
        assertTrue(insert.endsWith(")"));
    }

    @Test
    void Update_command_sets_aggregates_and_keeps_the_table_placeholder() {
        var s = new sum_voice_day_03();
        s.id = 5; s.totalcalls = 2; s.customercost = new BigDecimal("1.5");

        var update = s.GetUpdateCommand(x -> "where id=" + x.id).toString();
        assertTrue(update.contains("update AbstractCdrSummary set"));   // placeholder the context replaces
        assertTrue(update.contains("totalcalls=2"));
        assertTrue(update.contains("customercost=1.5"));
        assertTrue(update.contains("where id=5"));
    }

    @Test
    void Insert_columns_list_count_matches_the_value_count() {
        var columns = AbstractCdrSummary.ExtInsertColumns.split(",", -1).length;
        var values = new sum_voice_day_03().GetExtInsertValues().toString().split(",", -1).length;
        assertEquals(columns, values);   // header column count == value tuple field count
    }
}
