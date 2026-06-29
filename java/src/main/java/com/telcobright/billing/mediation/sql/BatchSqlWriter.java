package com.telcobright.billing.mediation.sql;

import java.util.List;

/**
 * The single-connection BATCH SQL writer — the lean port of legacy CollectionSegmenter +
 * DbWriterWithAccurateCount. It writes ANY number of rows as multi-row INSERTs sliced into segments of
 * {@code segmentSize} (legacy {@code CdrSetting.SegmentSizeForDbWrite}), so a large CDR batch
 * never exceeds {@code max_allowed_packet}. Each segment becomes one
 * {@code insertHeader + join(",", valueTuples)} statement run through the {@link ISqlExecutor}; the
 * affected-row counts are summed. The legacy stored-proc accurate-count is replaced by
 * {@link ISqlExecutor#ExecuteNonQuery}'s return value.
 */
public final class BatchSqlWriter {
    private BatchSqlWriter() {}

    /** The legacy default {@code SegmentSizeForDbWrite} — rows per multi-row insert. */
    public static final int DefaultSegmentSize = 1000;

    /** Write the value tuples as segmented multi-row INSERTs; returns total affected rows. */
    public static int WriteInsertsInSegments(ISqlExecutor executor, String insertHeader,
            List<StringBuilder> valueTuples, int segmentSize) {
        if (valueTuples == null || valueTuples.isEmpty()) return 0;

        // int[] holds the running count so the segment lambda can mutate it (Java captures are effectively-final).
        var affected = new int[]{0};
        var segmenter = new CollectionSegmenter<StringBuilder>(valueTuples, 0);
        segmenter.ExecuteMethodInSegments(segmentSize, segment -> {
            var sql = new StringBuilder(insertHeader).append(String.join(",", segment)).toString();
            affected[0] += executor.ExecuteNonQuery(sql);
        });
        return affected[0];
    }

    /** Write the value tuples as segmented multi-row INSERTs; returns total affected rows. */
    public static int WriteInsertsInSegments(ISqlExecutor executor, String insertHeader,
            List<StringBuilder> valueTuples) {
        return WriteInsertsInSegments(executor, insertHeader, valueTuples, DefaultSegmentSize);
    }

    /** Run each pre-built statement in segments (e.g. per-row UPDATE/DELETE), one ExecuteNonQuery
     * per segment with the statements concatenated; returns total affected rows. */
    public static int WriteStatementsInSegments(ISqlExecutor executor,
            List<String> statements, int segmentSize) {
        if (statements == null || statements.isEmpty()) return 0;

        var affected = new int[]{0};
        var segmenter = new CollectionSegmenter<String>(statements, 0);
        segmenter.ExecuteMethodInSegments(segmentSize, segment -> {
            var sql = new StringBuilder();
            for (var s : segment) {
                sql.append(s);
                if (!s.stripTrailing().endsWith(";")) sql.append(';');
            }
            affected[0] += executor.ExecuteNonQuery(sql.toString());
        });
        return affected[0];
    }

    /** Run each pre-built statement in segments; returns total affected rows. */
    public static int WriteStatementsInSegments(ISqlExecutor executor, List<String> statements) {
        return WriteStatementsInSegments(executor, statements, DefaultSegmentSize);
    }
}
