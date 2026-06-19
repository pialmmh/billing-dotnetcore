namespace TelcobrightMediation;

/// <summary>
/// Runs one SQL statement on the single shared MySqlConnection (and its transaction) and returns the
/// affected-row count — the thin replacement for the legacy <c>DbCommand</c> + <c>DbWriterWithAccurateCount</c>
/// stored-proc / <c>CollectionSegmenter</c> / <c>ParallelIterator</c> batch executor. The live implementation
/// wraps one <c>MySqlCommand</c> on the per-call connection; tests use an in-memory capture.
/// </summary>
public interface ISqlExecutor
{
    int ExecuteNonQuery(string sql);
}
