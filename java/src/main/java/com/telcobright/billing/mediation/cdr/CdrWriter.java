package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.sql.ISqlExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes {@link cdr} rows through the batch's single-connection {@link ISqlExecutor} — the lean
 * port of legacy {@code CdrProcessor.WriteCdrs}. The rows are handed to {@link BatchSqlWriter}, which
 * emits them as segmented multi-row inserts (the ported CollectionSegmenter) so any batch size is safe. Same
 * connection/transaction as the chargeable + summary writes, so a batch persists together. The
 * {@code table} selects the destination — {@code cdr} for qualified calls, {@code cdrerror} for
 * rejected ones (the cdrerror table has the SAME columns, legacy _StaticExtInsertColumnHeaders). (cdr has no
 * auto-id column — its key is the natural UniqueBillId/composite — so nothing is assigned here.)
 */
public final class CdrWriter {
    private CdrWriter() {}

    public static int Write(ISqlExecutor sql, List<cdr> cdrs, int segmentSize, String table) {
        if (cdrs.isEmpty()) return 0;

        var header = "insert into " + table + " (" + cdr.ExtInsertColumns + ") values ";
        var values = new ArrayList<StringBuilder>(cdrs.size());
        for (var c : cdrs) values.add(c.GetExtInsertValues());
        return BatchSqlWriter.WriteInsertsInSegments(sql, header, values, segmentSize);
    }

    public static int Write(ISqlExecutor sql, List<cdr> cdrs, int segmentSize) {
        return Write(sql, cdrs, segmentSize, "cdr");
    }

    public static int Write(ISqlExecutor sql, List<cdr> cdrs) {
        return Write(sql, cdrs, BatchSqlWriter.DefaultSegmentSize, "cdr");
    }
}
