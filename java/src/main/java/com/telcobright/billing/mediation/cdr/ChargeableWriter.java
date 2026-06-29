package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.mediation.summary.cache.IAutoIncrementManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes the call legs' {@link acc_chargeable} rows through the batch's single-connection
 * {@link ISqlExecutor} — the lean port of legacy {@code CdrProcessor.ProcessChargeables}/{@code WriteCdrs}'
 * chargeable insert. New rows get an id from the {@link IAutoIncrementManager} (keyed by table name),
 * then the rows are handed to {@link BatchSqlWriter} which emits them as segmented multi-row inserts
 * (the ported legacy CollectionSegmenter) so any batch size is safe. Same connection/transaction as the
 * summary write, so a call's chargeables + summaries commit atomically.
 */
public final class ChargeableWriter {
    private ChargeableWriter() {}

    public static int Write(ISqlExecutor sql, List<acc_chargeable> chargeables, IAutoIncrementManager ids,
            int segmentSize) {
        if (chargeables.isEmpty()) return 0;

        var header = new StringBuilder("insert into acc_chargeable (")
                .append(acc_chargeable.ExtInsertColumns).append(") values ").toString();
        var values = new ArrayList<StringBuilder>(chargeables.size());
        for (var c : chargeables) {
            if (c.id <= 0) c.id = ids.GetNewCounter("acc_chargeable");
            values.add(c.GetExtInsertValues());
        }
        return BatchSqlWriter.WriteInsertsInSegments(sql, header, values, segmentSize);
    }

    public static int Write(ISqlExecutor sql, List<acc_chargeable> chargeables, IAutoIncrementManager ids) {
        return Write(sql, chargeables, ids, BatchSqlWriter.DefaultSegmentSize);
    }
}
