package com.telcobright.billing.mediation.summary;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.telcobright.billing.mediation.cdr.Entry;
import com.telcobright.billing.mediation.cdr.SummaryOutboxWriter;
import com.telcobright.billing.mediation.engine.models.AbstractCdrSummary;
import com.telcobright.billing.mediation.engine.models.CdrSummaryType;
import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.sql.BatchSqlWriter;
import com.telcobright.billing.mediation.sql.IAutoIncrementManager;

/**
 * Folds a page of decoded {@code summary_affected} outbox rows into the {@code sum_voice_day/hr} tables — the
 * CONSUMER side of the summary hand-off, the roll-up the pipeline no longer does inline (it is OUTBOX-only).
 *
 * <p>It drives a single {@link CdrSummaryContext} exactly as the legacy inline summary phase did
 * ({@link CdrSummaryContext#PopulatePrevSummary} once for every day/hour bucket the page touches, then
 * merge-add each call onto its loaded bucket), so a page spanning multiple batches / days / hours merges onto
 * the existing rows instead of duplicating them — and the roll-up is BY SERVICE GROUP (SG10&nbsp;&rarr;
 * {@code sum_voice_day_03}/{@code hr_03}, SG11&nbsp;&rarr; {@code _02}) and TUPLE-WISE (aggregated by
 * {@link AbstractCdrSummary#GetTupleKey()}). {@code subtract} rows (corrections) negate their calls off the
 * same buckets.</p>
 *
 * <p>Pure w.r.t. the database: everything flows through the caller-supplied {@link ISummaryStore} (prev-load +
 * write) and {@link IAutoIncrementManager} (id source) — the same seams the inline engine used — so it is
 * unit-testable against an in-memory store with no live DB.</p>
 */
public final class SummaryRollup {
    private SummaryRollup() {}

    /** One decoded outbox row: its id (the offset cursor), its op ({@code add}/{@code subtract}), its calls. */
    public record OutboxRow(long id, String op, List<Entry> entries) {}

    /** Fold the page into the sum_voice caches and flush; returns the number of calls folded (customer legs). */
    public static int Apply(ISummaryStore store, IAutoIncrementManager ids, List<OutboxRow> rows, int segmentSize) {
        CdrSummaryContext context = new CdrSummaryContext(store, ids);

        // The service groups + buckets this page touches — the legacy DatesInvolved/HoursInvolved, taken over
        // the whole page so PopulatePrevSummary loads each bucket ONCE (not once per row) and every call merges
        // onto it. Only customer legs matter (a failed/zero-charge leg has no customer chargeable to summarise).
        Set<Integer> serviceGroups = new LinkedHashSet<>();
        Set<LocalDateTime> hoursInvolved = new LinkedHashSet<>();
        Set<LocalDateTime> datesInvolved = new LinkedHashSet<>();
        for (OutboxRow row : rows) {
            for (Entry e : row.entries()) {
                acc_chargeable customer = e.Customer();
                if (customer == null) continue;
                serviceGroups.add(customer.servicegroup);
                LocalDateTime start = e.Cdr().StartTime;
                hoursInvolved.add(start.truncatedTo(ChronoUnit.HOURS));         // matches CdrSummaryBuilder hour bucket
                datesInvolved.add(start.toLocalDate().atStartOfDay());          // matches CdrSummaryBuilder day bucket
            }
        }
        context.PopulatePrevSummary(new ArrayList<>(serviceGroups),
                new ArrayList<>(datesInvolved), new ArrayList<>(hoursInvolved));

        int folded = 0;
        for (OutboxRow row : rows) {
            boolean subtract = SummaryOutboxWriter.OpSubtract.equals(row.op());
            for (Entry e : row.entries()) {
                acc_chargeable customer = e.Customer();
                if (customer == null) continue;
                if (subtract) {
                    for (Map.Entry<CdrSummaryType, AbstractCdrSummary> s :
                            context.GenerateSummary(e.Cdr(), customer).entrySet())
                        context.MergeSubstractSummary(s.getKey(), s.getValue());
                } else {
                    context.AddCall(e.Cdr(), customer);
                }
                folded++;
            }
        }

        context.WriteAllChanges(segmentSize);
        return folded;
    }

    /** Fold the page with the default write segment size. */
    public static int Apply(ISummaryStore store, IAutoIncrementManager ids, List<OutboxRow> rows) {
        return Apply(store, ids, rows, BatchSqlWriter.DefaultSegmentSize);
    }
}
