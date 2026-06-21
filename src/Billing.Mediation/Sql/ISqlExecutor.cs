namespace Billing.Mediation.Sql;

/// <summary>
/// Runs one SQL statement on the single shared MySqlConnection (and its transaction) and returns the
/// affected-row count — the thin replacement for the legacy <c>DbCommand</c> + <c>DbWriterWithAccurateCount</c>
/// stored proc. Batching is layered on top by <see cref="BatchSqlWriter"/> (the ported
/// <c>CollectionSegmenter</c>). The live implementation wraps one <c>MySqlCommand</c> on the per-call
/// connection; tests use an in-memory capture.
/// </summary>
public interface ISqlExecutor
{
    int ExecuteNonQuery(string sql);
}
