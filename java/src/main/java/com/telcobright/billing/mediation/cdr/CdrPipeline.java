package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.rating.BasicCharge;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;
import com.telcobright.billing.mediation.sql.IAutoIncrementManager;
import com.telcobright.billing.mediation.validation.MediationValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * The decoupled CDR PROCESSING pipeline — the port of legacy {@code CdrProcessor}'s
 * mediate → write phases, fed an ALREADY-FETCHED batch of cdrs for ONE tenant.
 *
 * <p>Job processing is a SEPARATE concern (legacy {@code CdrJobProcessor}): fetching/decoding jobs per
 * tenant + NE, prefetch, merge, error handling, commits. That layer later produces the {@code List<cdr>}
 * and hands it here — this pipeline never touches jobs, files, switches or the scheduler. The eventual
 * driver reads:</p>
 * <pre>{@code
 * for each tenant:
 *     var cdrs = jobLayer.FetchAndDecode(tenant);     // legacy CdrJobProcessor (later)
 *     processor.Process(new CdrBatch(tenant.Mediation, tenant.Partners, cdrs, store));
 * }</pre>
 *
 * The phases mirror the legacy:
 * <ol>
 * <li><b>Mediate</b> — per cdr: detect the service group → run the SG's configured rating rules via the
 *   per-day {@code RateCache} ({@link BasicCharge}) → the call's {@code acc_chargeable}s.</li>
 * <li><b>Qualify</b> — validate each mediated cdr against the checklists ({@link MediationValidator}):
 *   the common checklist + the SG's answered/unanswered checklist. A rejected cdr (or one that produced no
 *   chargeable) gets its {@code ErrorCode} set and is routed to {@code cdrerror} instead of {@code cdr}.</li>
 * <li><b>Write</b> — the qualified cdrs + their chargeables, the rejected cdrs to {@code cdrerror}, AND the
 *   summary OUTBOX row, all through the batch's single-connection segmented writer in ONE transaction.</li>
 * </ol>
 *
 * <p>Summaries are OUTBOX-ONLY: the batch writes ONE compressed {@code summary_affected} row (the rated cdrs
 * + ALL their chargeable legs) atomically with the cdr/chargeable write; the standalone summary-service
 * consumes that row and rolls the totals up incrementally. The old inline roll-up engine has been removed.</p>
 */
public final class CdrPipeline {
    private final BasicCharge _basicCharge;

    public CdrPipeline(BasicCharge basicCharge) { _basicCharge = basicCharge; }

    /** The SG10+SG11 detection pair wired to the rating flow — the ready instance. */
    public static CdrPipeline Default() { return new CdrPipeline(BasicCharge.Default()); }

    public CdrBatchResult Process(CdrBatch batch) {
        // One id source for the whole batch, shared by the chargeable write (legacy IAutoIncrementManager).
        IAutoIncrementManager ids = batch.Ids() != null ? batch.Ids() : new CountingAutoIncrementManager();
        var rated = new ArrayList<RatedCdr>();
        var errored = new ArrayList<cdr>();

        // PHASE 0 — the legacy NewCdrPreProcessor's surviving duties: assign IdCall where the producer
        // didn't (it drives the cdr row identity AND chargeable.idEvent), and assert the batch's own
        // uniqueness — a duplicate UniqueBillId or IdCall aborts the batch BEFORE anything is written
        // (legacy threw the same way; silent duplicates here mean double billing).
        var seenBillIds = new HashSet<String>();
        var seenIdCalls = new HashSet<Long>();
        for (var thisCdr : batch.Cdrs()) {
            if (thisCdr.IdCall <= 0) thisCdr.IdCall = ids.GetNewCounter("cdr");
            if (thisCdr.UniqueBillId != null && !thisCdr.UniqueBillId.isEmpty()
                    && !seenBillIds.add(thisCdr.UniqueBillId))
                throw new IllegalStateException("duplicate UniqueBillId in batch: " + thisCdr.UniqueBillId);
            if (!seenIdCalls.add(thisCdr.IdCall))
                throw new IllegalStateException("duplicate IdCall in batch: " + thisCdr.IdCall);
        }

        // PHASE 1 — Mediate + Qualify: detect SG → run the SG's configured rating rules through the RateCache
        // (legacy ExecuteRating), then validate the cdr against the checklists (legacy MediationValidator)
        // BEFORE it can reach the cdr table. Rejected or unmediated cdrs are routed to cdrerror — including
        // ones whose mediation THROWS: one bad cdr must not poison the batch's good cdrs.
        for (var thisCdr : batch.Cdrs()) {
            try {
                var chargeables = _basicCharge.Rate(thisCdr, batch.Mediation(), batch.Partners());

                var error = MediationValidator.Validate(thisCdr, batch.Mediation());
                if (error.length() == 0 && chargeables.isEmpty()) error = "no chargeable produced";
                if (error.length() > 0) { thisCdr.ErrorCode = error; errored.add(thisCdr); continue; }

                rated.add(new RatedCdr(thisCdr, chargeables));
            } catch (RuntimeException mediationFailure) {
                thisCdr.ErrorCode = Truncate("mediation failed: " + mediationFailure.getMessage(), 255);
                errored.add(thisCdr);
            }
        }

        // PHASE 2 — Write (same single connection, segmented): the qualified cdr rows + their chargeables
        // land together, and the rejected cdrs go to cdrerror (legacy WriteCdrs + ProcessChargeables +
        // cdrerror).
        var cdrsWritten = CdrWriter.Write(batch.Sql(), rated.stream().map(r -> r.Cdr()).toList(), batch.SegmentSize());
        var cdrErrorsWritten = CdrWriter.Write(batch.Sql(), errored, batch.SegmentSize(), "cdrerror");

        var allChargeables = new ArrayList<acc_chargeable>();
        for (var r : rated) allChargeables.addAll(r.Chargeables());
        var chargeablesWritten = ChargeableWriter.Write(batch.Sql(), allChargeables, ids, batch.SegmentSize());

        // Summaries (OUTBOX-only): write ONE compressed row to summary_affected for the summary-service to
        // consume + roll up incrementally. It is the SAME tx as the cdr/chargeable write above — so the
        // summary input persists atomically with the cdr (no MySQL/Kafka dual-write gap).
        SummaryOutboxWriter.Write(batch.Sql(), rated);

        return new CdrBatchResult(rated, errored, cdrsWritten, cdrErrorsWritten, chargeablesWritten);
    }

    private static String Truncate(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max);
    }
}
