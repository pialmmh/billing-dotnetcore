using MediationModel;
using Billing.Mediation.Sql;

namespace Billing.Mediation.Cdr;

/// <summary>
/// Writes the mediated <see cref="cdr"/> rows through the batch's single-connection <see cref="ISqlExecutor"/>
/// — the lean port of legacy <c>CdrProcessor.WriteCdrs</c>. The rows are handed to <see cref="BatchSqlWriter"/>,
/// which emits them as segmented multi-row inserts (the ported CollectionSegmenter) so any batch size is safe.
/// Same connection/transaction as the chargeable + summary writes, so a batch persists together. (cdr has no
/// auto-id column — its key is the natural UniqueBillId/composite — so nothing is assigned here.)
/// </summary>
public static class CdrWriter
{
    public static int Write(ISqlExecutor sql, IReadOnlyList<cdr> cdrs, int segmentSize = BatchSqlWriter.DefaultSegmentSize)
    {
        if (cdrs.Count == 0) return 0;

        var header = $"insert into cdr ({cdr.ExtInsertColumns}) values ";
        var values = new List<System.Text.StringBuilder>(cdrs.Count);
        foreach (var c in cdrs) values.Add(c.GetExtInsertValues());
        return BatchSqlWriter.WriteInsertsInSegments(sql, header, values, segmentSize);
    }
}
