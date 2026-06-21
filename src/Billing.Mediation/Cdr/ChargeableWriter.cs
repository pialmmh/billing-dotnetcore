using System.Text;
using MediationModel;
using TelcobrightMediation;

namespace Billing.Mediation.Cdr;

/// <summary>
/// Writes the call legs' <see cref="acc_chargeable"/> rows through the batch's single-connection
/// <see cref="ISqlExecutor"/> — the lean port of legacy <c>CdrProcessor.ProcessChargeables</c>/<c>WriteCdrs</c>'
/// chargeable insert. New rows get an id from the <see cref="IAutoIncrementManager"/> (keyed by table name)
/// and are emitted as ONE multi-row insert (<c>ExtInsertColumns</c> header + comma-joined value tuples),
/// matching the legacy batched insert. Same connection/transaction as the summary write, so a call's
/// chargeables + summaries commit atomically.
/// </summary>
public static class ChargeableWriter
{
    public static int Write(ISqlExecutor sql, IReadOnlyList<acc_chargeable> chargeables, IAutoIncrementManager ids)
    {
        if (chargeables.Count == 0) return 0;

        var insert = new StringBuilder("insert into acc_chargeable (")
            .Append(acc_chargeable.ExtInsertColumns).Append(") values ");
        for (var i = 0; i < chargeables.Count; i++)
        {
            var c = chargeables[i];
            if (c.id <= 0) c.id = ids.GetNewCounter("acc_chargeable");
            if (i > 0) insert.Append(',');
            insert.Append(c.GetExtInsertValues());
        }
        return sql.ExecuteNonQuery(insert.ToString());
    }
}
