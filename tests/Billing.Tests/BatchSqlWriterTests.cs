using System.Text;
using Billing.Mediation.Sql;

namespace Billing.Tests;

/// <summary>The ported segmented batch writer (legacy CollectionSegmenter + DbWriterWithAccurateCount):
/// any number of rows write as multi-row INSERTs sliced into segmentSize chunks, so one statement never
/// exceeds max_allowed_packet. Affected counts sum across segments.</summary>
public class BatchSqlWriterTests
{
    // Captures each statement; reports affected = number of value tuples in that statement.
    private sealed class CapturingExecutor : ISqlExecutor
    {
        public List<string> Sql { get; } = new();
        public int ExecuteNonQuery(string sql) { Sql.Add(sql); return sql.Split("),(").Length; }
    }

    private static List<StringBuilder> Rows(int n)
    {
        var rows = new List<StringBuilder>(n);
        for (var i = 0; i < n; i++) rows.Add(new StringBuilder($"({i})"));
        return rows;
    }

    [Fact]
    public void Large_batch_splits_into_segments()
    {
        var exec = new CapturingExecutor();
        var affected = BatchSqlWriter.WriteInsertsInSegments(exec, "insert into t (c) values ", Rows(5), segmentSize: 2);

        Assert.Equal(3, exec.Sql.Count);                 // 5 rows / 2 = 3 segments (2,2,1)
        Assert.All(exec.Sql, s => Assert.StartsWith("insert into t (c) values ", s));
        Assert.Equal(2, exec.Sql[0].Split("),(").Length);   // segment 1 has 2 value tuples
        Assert.DoesNotContain("),(", exec.Sql[2]);          // last segment has the remaining single tuple
        Assert.Equal(5, affected);                          // total rows written
    }

    [Fact]
    public void Single_segment_when_batch_fits()
    {
        var exec = new CapturingExecutor();
        var affected = BatchSqlWriter.WriteInsertsInSegments(exec, "insert into t (c) values ", Rows(3));  // default 1000

        Assert.Single(exec.Sql);
        Assert.Equal(3, affected);
    }

    [Fact]
    public void Empty_batch_writes_nothing()
    {
        var exec = new CapturingExecutor();
        Assert.Equal(0, BatchSqlWriter.WriteInsertsInSegments(exec, "insert into t (c) values ", Rows(0)));
        Assert.Empty(exec.Sql);
    }

    [Fact]
    public void Statements_run_in_segments_each_terminated()
    {
        var exec = new CapturingExecutor();
        var updates = new List<string> { "update t set a=1 where id=1", "update t set a=2 where id=2", "update t set a=3 where id=3" };
        BatchSqlWriter.WriteStatementsInSegments(exec, updates, segmentSize: 2);

        Assert.Equal(2, exec.Sql.Count);                 // (2 + 1)
        Assert.EndsWith(";", exec.Sql[0]);               // statements terminated
    }
}
