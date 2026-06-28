using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using Billing.Mediation.Sql;
using Billing.Mediation.Summary;
using Billing.Mediation.Validation;
using MediationModel;
using TelcobrightMediation;

namespace Billing.Mediation.Cdr;

/// <summary>
/// The decoupled CDR PROCESSING pipeline — the port of legacy <c>CdrProcessor</c>'s
/// mediate → summarize → write phases, fed an ALREADY-FETCHED batch of cdrs for ONE tenant.
///
/// Job processing is a SEPARATE concern (legacy <c>CdrJobProcessor</c>): fetching/decoding jobs per
/// tenant + NE, prefetch, merge, error handling, commits. That layer later produces the <c>List&lt;cdr&gt;</c>
/// and hands it here — this pipeline never touches jobs, files, switches or the scheduler. The eventual
/// driver reads:
/// <code>
/// for each tenant:
///     var cdrs = jobLayer.FetchAndDecode(tenant);     // legacy CdrJobProcessor (later)
///     processor.Process(new CdrBatch(tenant.Mediation, tenant.Partners, cdrs, store));
/// </code>
///
/// The phases mirror the legacy:
/// <list type="number">
/// <item><b>Mediate</b> — per cdr: detect the service group → run the SG's configured rating rules via the
///   per-day <c>RateCache</c> (<see cref="BasicCharge"/>) → the call's <c>acc_chargeable</c>s.</item>
/// <item><b>Qualify</b> — validate each mediated cdr against the checklists (<see cref="MediationValidator"/>):
///   the common checklist + the SG's answered/unanswered checklist. A rejected cdr (or one that produced no
///   chargeable) gets its <c>ErrorCode</c> set and is routed to <c>cdrerror</c> instead of <c>cdr</c>.</item>
/// <item><b>Summaries</b> — per QUALIFIED cdr: pre-load the involved buckets once and merge-add the call
///   (<see cref="CdrSummaryContext"/>).</item>
/// <item><b>Write</b> — the qualified cdrs + their chargeables + the summaries, AND the rejected cdrs to
///   cdrerror, all through the batch's single-connection segmented writer.</item>
/// </list>
/// </summary>
public sealed class CdrPipeline
{
    private readonly BasicCharge _basicCharge;

    public CdrPipeline(BasicCharge basicCharge) => _basicCharge = basicCharge;

    /// <summary>The SG10+SG11 detection pair wired to the rating flow — the ready instance.</summary>
    public static CdrPipeline Default() => new(BasicCharge.Default());

    public CdrBatchResult Process(CdrBatch batch)
    {
        // One id source for the whole batch, shared by the chargeable + summary writes (legacy IAutoIncrementManager).
        var ids = batch.Ids ?? new CountingAutoIncrementManager();
        var rated = new List<RatedCdr>();
        var errored = new List<cdr>();

        // PHASE 1 — Mediate + Qualify: detect SG → run the SG's configured rating rules through the RateCache
        // (legacy ExecuteRating), then validate the cdr against the checklists (legacy MediationValidator)
        // BEFORE it can reach the summary/cdr table. Rejected or unmediated cdrs are routed to cdrerror.
        foreach (var thisCdr in batch.Cdrs)
        {
            var chargeables = _basicCharge.Rate(thisCdr, batch.Mediation, batch.Partners);

            var error = MediationValidator.Validate(thisCdr, batch.Mediation);
            if (error.Length == 0 && chargeables.Count == 0) error = "no chargeable produced";
            if (error.Length > 0) { thisCdr.ErrorCode = error; errored.Add(thisCdr); continue; }

            rated.Add(new RatedCdr(thisCdr, chargeables));
        }

        // PHASE 2 — Summaries (INLINE mode only): pre-load ALL day/hour buckets the batch touches (legacy
        // DatesInvolved/HoursInvolved — distinct hours of the rated cdrs, and their dates), once per table;
        // then merge-add each rated call onto its loaded bucket. In OUTBOX mode this is skipped — the
        // decoupled summary-service does the roll-up from the outbox row written in PHASE 3.
        CdrSummaryContext? summary = null;
        if (batch.Summary == SummaryMode.Inline && rated.Count > 0)
        {
            summary = new CdrSummaryContext(batch.SummaryStore, ids);
            var hoursInvolved = rated.Select(r => HourOf(r.Cdr.StartTime)).Distinct().ToList();
            var datesInvolved = hoursInvolved.Select(h => h.Date).Distinct().ToList();
            summary.PopulatePrevSummary(batch.Mediation.ServiceGroupConfigurations.Keys, datesInvolved, hoursInvolved);
            foreach (var r in rated)
            {
                if (r.Customer is null) continue;
                summary.AddCall(r.Cdr, r.Customer);
            }
        }

        // PHASE 3 — Write (same single connection, segmented): the qualified cdr rows + their chargeables +
        // the summaries land together, and the rejected cdrs go to cdrerror (legacy WriteCdrs +
        // ProcessChargeables + summary write + cdrerror).
        var cdrsWritten = CdrWriter.Write(batch.SummaryStore, rated.ConvertAll(r => r.Cdr), batch.SegmentSize);
        var cdrErrorsWritten = CdrWriter.Write(batch.SummaryStore, errored, batch.SegmentSize, table: "cdrerror");

        var allChargeables = new List<acc_chargeable>();
        foreach (var r in rated) allChargeables.AddRange(r.Chargeables);
        var chargeablesWritten = ChargeableWriter.Write(batch.SummaryStore, allChargeables, ids, batch.SegmentSize);

        // Summaries: INLINE writes the rolled-up rows now; OUTBOX writes ONE compressed row to summary_affected
        // for the summary-service to consume + roll up. Either way it's the SAME tx as the cdr/chargeable write
        // above — so the summary input persists atomically with the cdr (no MySQL/Kafka dual-write gap).
        if (batch.Summary == SummaryMode.Outbox)
            SummaryOutboxWriter.Write(batch.SummaryStore, rated);
        else
            summary?.WriteAllChanges(batch.SegmentSize);

        return new CdrBatchResult(rated, errored, cdrsWritten, cdrErrorsWritten, chargeablesWritten);
    }

    private static DateTime HourOf(DateTime t) => new(t.Year, t.Month, t.Day, t.Hour, 0, 0);
}

/// <summary>The processing input for ONE tenant's already-fetched cdr batch: the tenant's rating config
/// (<see cref="MediationContext"/> + <see cref="Partners"/>), the cdrs, and the summary store the writes
/// flow through (wraps that tenant's single per-batch connection). The job layer assembles this.</summary>
public sealed record CdrBatch(
    MediationContext Mediation,
    IReadOnlyDictionary<int, Partner> Partners,
    IReadOnlyList<cdr> Cdrs,
    ISummaryStore SummaryStore,
    IAutoIncrementManager? Ids = null,
    int SegmentSize = BatchSqlWriter.DefaultSegmentSize,
    SummaryMode Summary = SummaryMode.Inline);

/// <summary>One mediated cdr: the call plus every chargeable its service group's configured rules produced
/// (customer + supplier legs). <see cref="Customer"/> is the customer-leg chargeable the summary reads.</summary>
public sealed record RatedCdr(cdr Cdr, IReadOnlyList<acc_chargeable> Chargeables)
{
    public acc_chargeable? Customer =>
        Chargeables.FirstOrDefault(c => c.assignedDirection == (sbyte)AssignmentDirection.Customer)
        ?? Chargeables.FirstOrDefault();
}

/// <summary>The batch outcome: the qualified calls (with chargeables, written to <c>cdr</c>), the rejected
/// cdrs (each with its <c>ErrorCode</c>, written to <c>cdrerror</c>), and the rows written per table.
/// <see cref="TotalCharged"/> sums the customer billed amounts.</summary>
public sealed record CdrBatchResult(
    IReadOnlyList<RatedCdr> Rated, IReadOnlyList<cdr> Errored,
    int CdrsWritten, int CdrErrorsWritten, int ChargeablesWritten)
{
    public int Total => Rated.Count + Errored.Count;
    public decimal TotalCharged => Rated.Sum(r => r.Customer?.BilledAmount ?? 0m);
}
