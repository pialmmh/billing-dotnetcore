using System.Collections.Generic;
using System.Text;
using LibraryExtensions;

namespace Billing.Mediation.Sql;

/// <summary>
/// The single-connection BATCH SQL writer — the lean port of legacy CollectionSegmenter +
/// DbWriterWithAccurateCount. It writes ANY number of rows as multi-row INSERTs sliced into segments of
/// <paramref name="segmentSize"/> (legacy <c>CdrSetting.SegmentSizeForDbWrite</c>), so a large CDR batch
/// never exceeds <c>max_allowed_packet</c>. Each segment becomes one
/// <c>insertHeader + join(",", valueTuples)</c> statement run through the <see cref="ISqlExecutor"/>; the
/// affected-row counts are summed. The legacy stored-proc accurate-count is replaced by
/// <see cref="ISqlExecutor.ExecuteNonQuery"/>'s return value.
/// </summary>
public static class BatchSqlWriter
{
    /// <summary>The legacy default <c>SegmentSizeForDbWrite</c> — rows per multi-row insert.</summary>
    public const int DefaultSegmentSize = 1000;

    /// <summary>Write the value tuples as segmented multi-row INSERTs; returns total affected rows.</summary>
    public static int WriteInsertsInSegments(ISqlExecutor executor, string insertHeader,
        IReadOnlyList<StringBuilder> valueTuples, int segmentSize = DefaultSegmentSize)
    {
        if (valueTuples == null || valueTuples.Count == 0) return 0;

        var affected = 0;
        var segmenter = new CollectionSegmenter<StringBuilder>(valueTuples, 0);
        segmenter.ExecuteMethodInSegments(segmentSize, segment =>
        {
            var sql = new StringBuilder(insertHeader).Append(string.Join(",", segment)).ToString();
            affected += executor.ExecuteNonQuery(sql);
        });
        return affected;
    }

    /// <summary>Run each pre-built statement in segments (e.g. per-row UPDATE/DELETE), one ExecuteNonQuery
    /// per segment with the statements concatenated; returns total affected rows.</summary>
    public static int WriteStatementsInSegments(ISqlExecutor executor,
        IReadOnlyList<string> statements, int segmentSize = DefaultSegmentSize)
    {
        if (statements == null || statements.Count == 0) return 0;

        var affected = 0;
        var segmenter = new CollectionSegmenter<string>(statements, 0);
        segmenter.ExecuteMethodInSegments(segmentSize, segment =>
        {
            var sql = new StringBuilder();
            foreach (var s in segment)
            {
                sql.Append(s);
                if (!s.TrimEnd().EndsWith(";")) sql.Append(';');
            }
            affected += executor.ExecuteNonQuery(sql.ToString());
        });
        return affected;
    }
}
