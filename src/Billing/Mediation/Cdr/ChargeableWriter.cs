using System.Text;
using Billing.Mediation.Sql;
using MediationModel;

namespace Billing.Mediation.Cdr;

/// <summary>
/// Writes the call legs' <see cref="acc_chargeable"/> rows through the batch's single-connection
/// <see cref="ISqlExecutor"/> — the lean port of legacy <c>CdrProcessor.ProcessChargeables</c>/<c>WriteCdrs</c>'
/// chargeable insert. New rows get an id from the <see cref="IAutoIncrementManager"/> (keyed by table name),
/// then the rows are handed to <see cref="BatchSqlWriter"/> which emits them as segmented multi-row inserts
/// (the ported legacy CollectionSegmenter) so any batch size is safe. Same connection/transaction as the
/// summary write, so a call's chargeables + summaries commit atomically.
/// </summary>
public static class ChargeableWriter
{
    public static int Write(ISqlExecutor sql, IReadOnlyList<acc_chargeable> chargeables, IAutoIncrementManager ids,
        int segmentSize = BatchSqlWriter.DefaultSegmentSize)
    {
        if (chargeables.Count == 0) return 0;

        var header = new StringBuilder("insert into acc_chargeable (")
            .Append(acc_chargeable.ExtInsertColumns).Append(") values ").ToString();
        var values = new List<StringBuilder>(chargeables.Count);
        foreach (var c in chargeables)
        {
            if (c.id <= 0) c.id = ids.GetNewCounter("acc_chargeable");
            values.Add(c.GetExtInsertValues());
        }
        return BatchSqlWriter.WriteInsertsInSegments(sql, header, values, segmentSize);
    }
}
