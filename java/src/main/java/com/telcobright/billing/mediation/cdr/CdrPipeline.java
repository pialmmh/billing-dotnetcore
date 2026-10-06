package com.telcobright.billing.mediation.cdr;

import com.telcobright.billing.mediation.engine.models.acc_chargeable;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.rating.BasicCharge;
import com.telcobright.billing.mediation.sql.CountingAutoIncrementManager;
import com.telcobright.billing.mediation.sql.IAutoIncrementManager;
import com.telcobright.billing.mediation.sms.ISmsAccountingStore;
import com.telcobright.billing.mediation.sms.SmsBillingRuleCatalog;
import com.telcobright.billing.mediation.sms.SmsOutgoingStage;
import com.telcobright.billing.mediation.validation.MediationValidator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

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
    /** Non-null ONLY on the outgoing-SMS pipeline ({@link #SmsOutgoing}); null = the voice pipeline, unchanged. */
    private final SmsOutgoingStage _sms;

    public CdrPipeline(BasicCharge basicCharge) { this(basicCharge, null); }

    private CdrPipeline(BasicCharge basicCharge, SmsOutgoingStage sms) {
        _basicCharge = basicCharge;
        _sms = sms;
    }

    /** The SG10+SG11 detection pair wired to the rating flow — the ready instance. */
    public static CdrPipeline Default() { return new CdrPipeline(BasicCharge.Default()); }

    /**
     * The OUTGOING-SMS pipeline (Phase 1): every cdr is SG20 ({@link BasicCharge#SmsOutgoing} — no voice
     * detection), rated SF10 with the calling+called composite matcher, qualified against the fixed SG20 checklist,
     * and — unlike voice — POSTED: account create/update, {@code acc_transaction}, balances and
     * {@code acc_ledger_summary}, in the same transaction as the cdr/chargeable/summary_affected writes.
     * Built per batch: {@code catalog} and {@code accounting} are bound to that batch's connection.
     */
    public static CdrPipeline SmsOutgoing(SmsBillingRuleCatalog catalog, ISmsAccountingStore accounting,
            Supplier<LocalDateTime> clock) {
        return new CdrPipeline(BasicCharge.SmsOutgoing(catalog), new SmsOutgoingStage(catalog, accounting, clock));
    }

    public static CdrPipeline SmsOutgoing(SmsBillingRuleCatalog catalog, ISmsAccountingStore accounting) {
        return SmsOutgoing(catalog, accounting, null);
    }

    /** True for the outgoing-SMS pipeline. */
    public boolean IsSmsOutgoing() { return _sms != null; }

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

                var error = _sms == null
                        ? MediationValidator.Validate(thisCdr, batch.Mediation())
                        : _sms.Validate(thisCdr, batch.Mediation());
                // DURATION-BASED billing guard. The primary rule is duration, not the answered flag:
                //   DurationSec == 0 => a legitimate FAILED call: NOT rated (legacy required no rate) — an empty
                //                       chargeable is EXPECTED. It is written to the normal cdr table (call-attempt
                //                       /ASR stats + summary), NEVER dumped to cdrerror for lacking a charge.
                //   DurationSec  > 0 => a BILLABLE call: it MUST have produced a CUSTOMER (revenue) leg — i.e. a
                //                       customer rate MATCHED. A matched rate of amount 0 is a VALID zero-rated
                //                       call (the rate table is the source of truth) and stays in cdr; only when
                //                       NO customer rate matched is it RATE_NOT_FOUND -> cdrerror (recover manually
                //                       after fixing config). Keying on the CUSTOMER leg (not chargeables.isEmpty())
                //                       also stops an ICX-cost-only, revenue-less call from slipping into cdr.
                boolean billable = thisCdr.DurationSec != null && thisCdr.DurationSec.signum() > 0;
                if (billable && error.length() == 0 && !CustomerChargeGuard.HasCustomerLeg(chargeables))
                    error = "RATE_NOT_FOUND: no customer charge produced (no rate matched)";
                if (error.length() > 0) { thisCdr.ErrorCode = Truncate(error, ErrorCap()); errored.add(thisCdr); continue; }
                // SMS: the accounting key (billing rule + posting account) must resolve while the cdr can still
                // go to cdrerror — a throw here routes it there instead of billing it without its ledger posting.
                if (_sms != null) _sms.Qualify(thisCdr, chargeables);

                rated.add(new RatedCdr(thisCdr, chargeables));
            } catch (RuntimeException mediationFailure) {
                thisCdr.ErrorCode = Truncate("mediation failed: " + mediationFailure.getMessage(), ErrorCap());
                errored.add(thisCdr);
            }
        }

        // SMS ONLY — post the batch's accounting (accounts / acc_transaction / balances / acc_ledger_summary +
        // the cdr meta totals and glAccountId on each chargeable) BEFORE the rows below are written, all in the
        // same transaction; then fit the error rows to the narrower SMS cdrerror columns.
        if (_sms != null) {
            _sms.MarkMediation(rated, errored);
            _sms.PostAccounting(batch.Sql(), rated, ids, batch.SegmentSize());
            _sms.FitErrorRows(errored);
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

    /**
     * Hard cap matching {@code cdrerror.ErrorCode VARCHAR(512)}. EVERY ErrorCode assignment must pass through
     * {@link #Truncate} with this cap: on 2026-09-03 a 228-char SG15 no-rate diagnostic overflowed the then-
     * varchar(100) column, the cdrerror INSERT threw Data-truncation, the atomic batch rolled back, and the
     * Kafka rewind replayed the same poison batch for 3h17m — an oversize message must degrade to a truncated
     * message, never to a stalled pipeline.
     */
    private static final int ErrorCodeMaxLen = 512;

    /** The ErrorCode cap for THIS pipeline: voice {@link #ErrorCodeMaxLen} (unchanged); SMS the SMS-schema cdrerror width (100). */
    private int ErrorCap() {
        return _sms == null ? ErrorCodeMaxLen : com.telcobright.billing.mediation.sms.SmsOutgoing.ErrorCodeMaxLen;
    }

    private static String Truncate(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max);
    }
}
