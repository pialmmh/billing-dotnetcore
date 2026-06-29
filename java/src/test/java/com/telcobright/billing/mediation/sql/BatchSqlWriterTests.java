// Faithful port of tests/Billing.Tests/BatchSqlWriterTests.cs (xUnit -> JUnit 5).
// Same package as the SUT (BatchSqlWriter) per RULE T0.
package com.telcobright.billing.mediation.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The ported segmented batch writer (legacy CollectionSegmenter + DbWriterWithAccurateCount): any number of
 * rows write as multi-row INSERTs sliced into segmentSize chunks, so one statement never exceeds
 * max_allowed_packet. Affected counts sum across segments.
 */
class BatchSqlWriterTests {

    // Captures each statement; reports affected = number of value tuples in that statement.
    // C# Split("),(") keeps trailing empties; the Java equivalent is split(regex, -1) with the parens escaped.
    private static final class CapturingExecutor implements ISqlExecutor {
        final List<String> Sql = new ArrayList<>();
        @Override public int ExecuteNonQuery(String sql) { Sql.add(sql); return sql.split("\\),\\(", -1).length; }
    }

    private static List<StringBuilder> Rows(int n) {
        var rows = new ArrayList<StringBuilder>(n);
        for (var i = 0; i < n; i++) rows.add(new StringBuilder("(" + i + ")"));
        return rows;
    }

    @Test
    void Large_batch_splits_into_segments() {
        var exec = new CapturingExecutor();
        var affected = BatchSqlWriter.WriteInsertsInSegments(exec, "insert into t (c) values ", Rows(5), 2);

        assertEquals(3, exec.Sql.size());                 // 5 rows / 2 = 3 segments (2,2,1)
        for (var s : exec.Sql) assertTrue(s.startsWith("insert into t (c) values "));
        assertEquals(2, exec.Sql.get(0).split("\\),\\(", -1).length);   // segment 1 has 2 value tuples
        assertFalse(exec.Sql.get(2).contains("),("));     // last segment has the remaining single tuple
        assertEquals(5, affected);                         // total rows written
    }

    @Test
    void Single_segment_when_batch_fits() {
        var exec = new CapturingExecutor();
        var affected = BatchSqlWriter.WriteInsertsInSegments(exec, "insert into t (c) values ", Rows(3));  // default 1000

        assertEquals(1, exec.Sql.size());
        assertEquals(3, affected);
    }

    @Test
    void Empty_batch_writes_nothing() {
        var exec = new CapturingExecutor();
        assertEquals(0, BatchSqlWriter.WriteInsertsInSegments(exec, "insert into t (c) values ", Rows(0)));
        assertTrue(exec.Sql.isEmpty());
    }

    @Test
    void Statements_run_in_segments_each_terminated() {
        var exec = new CapturingExecutor();
        var updates = List.of("update t set a=1 where id=1", "update t set a=2 where id=2", "update t set a=3 where id=3");
        BatchSqlWriter.WriteStatementsInSegments(exec, updates, 2);

        assertEquals(2, exec.Sql.size());                 // (2 + 1)
        assertTrue(exec.Sql.get(0).endsWith(";"));        // statements terminated
    }
}
