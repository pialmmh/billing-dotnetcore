using Billing.Mediation.Cdr;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Sql;
using MediationModel;
using MySqlConnector;

namespace Billing.Data;

/// <summary>
/// The TOP-LEVEL transaction boundary for ONE tenant's cdr batch — the legacy CdrJobProcessor's
/// <c>set autocommit=0 … commit / rollback</c>, at the high-level entry. It owns the connection's SINGLE
/// transaction: begin → run the whole <see cref="CdrProcessor"/> pipeline (which only EMITS SQL through the
/// tx-bound <see cref="MySqlSummaryStore"/>; NO inner class/method commits or rolls back) → commit. On ANY
/// exception the WHOLE batch rolls back. All-or-nothing: cdr + cdrerror + chargeables + summaries persist
/// together or not at all.
///
/// The caller owns the per-call connection (the architect's single-MySqlConnection rule); this owns the one
/// transaction around the batch. The future job-fetch layer hands the decoded cdrs here.
/// </summary>
public sealed class MySqlCdrBatchRunner
{
    private readonly CdrProcessor _processor;

    public MySqlCdrBatchRunner(CdrProcessor processor) => _processor = processor;

    public static MySqlCdrBatchRunner Default() => new(CdrProcessor.Default());

    public CdrBatchResult Run(MySqlConnection conn, MediationContext mediation,
        IReadOnlyDictionary<int, Partner> partners, IReadOnlyList<cdr> cdrs,
        IAutoIncrementManager? ids = null, int segmentSize = BatchSqlWriter.DefaultSegmentSize,
        SummaryMode summary = SummaryMode.Inline)
    {
        using var tx = conn.BeginTransaction();
        try
        {
            // the pipeline writes EVERYTHING through this tx-bound store — one connection, one transaction.
            var store = new MySqlSummaryStore(conn, tx);
            var batch = new CdrBatch(mediation, partners, cdrs, store, ids, segmentSize, summary);
            var result = _processor.Process(batch);
            tx.Commit();        // the ONE commit for the batch
            return result;
        }
        catch
        {
            tx.Rollback();      // the ONE rollback — undo the whole batch
            throw;
        }
    }
}
