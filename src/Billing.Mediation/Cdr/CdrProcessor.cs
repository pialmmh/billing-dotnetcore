using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
using Billing.Mediation.Sql;
using Billing.Mediation.Summary;
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
/// <item><b>Mediate</b> — per cdr: detect the service group → rate via the per-day <c>RateCache</c>
///   (<see cref="BasicCharge"/>) → <c>acc_chargeable</c> (customer leg, plus the supplier leg when a
///   supplier tuple resolves). Unrated cdrs are collected, not charged.</item>
/// <item><b>Summaries</b> — per rated cdr: load the prev summary rows once and merge-add this call onto
///   them (<see cref="CdrSummaryContext"/>).</item>
/// <item><b>Write</b> — flush all summary inserts/updates through the batch's single-connection store.</item>
/// </list>
/// The chargeable/cdr ROW writes ride the same store/single-connection pattern and are surfaced on the
/// result for the (pending) chargeable writer.
/// </summary>
public sealed class CdrProcessor
{
    private readonly BasicCharge _basicCharge;

    public CdrProcessor(BasicCharge basicCharge) => _basicCharge = basicCharge;

    /// <summary>The SG10+SG11 detection pair wired to the rating flow — the ready instance.</summary>
    public static CdrProcessor Default() => new(BasicCharge.Default());

    public CdrBatchResult Process(CdrBatch batch)
    {
        // One id source for the whole batch, shared by the chargeable + summary writes (legacy IAutoIncrementManager).
        var ids = batch.Ids ?? new CountingAutoIncrementManager();
        var summary = new CdrSummaryContext(batch.SummaryStore, ids);
        var rated = new List<RatedCdr>();
        var unrated = new List<cdr>();

        // PHASE 1 — Mediate: detect SG → run the SG's CONFIGURED rating rules through the RateCache
        // (legacy ExecuteRating) → the call's chargeables (customer + supplier legs), per cdr.
        foreach (var thisCdr in batch.Cdrs)
        {
            var chargeables = _basicCharge.Rate(thisCdr, batch.Mediation, batch.Partners);
            if (chargeables.Count == 0) { unrated.Add(thisCdr); continue; }
            rated.Add(new RatedCdr(thisCdr, chargeables));
        }

        // PHASE 2 — Summaries: load each rated call's prev rows (once per table+bucket) and merge-add it
        // (keyed off the customer-leg chargeable).
        foreach (var r in rated)
        {
            if (r.Customer is null) continue;
            summary.PopulatePrevSummary(new[] { r.Customer.servicegroup }, r.Cdr.StartTime.Date, HourOf(r.Cdr.StartTime));
            summary.AddCall(r.Cdr, r.Customer);
        }

        // PHASE 3 — Write (same single connection, segmented): the mediated cdr rows, the chargeable rows
        // (every rule's leg), then the summaries — so a batch's cdrs + chargeables + summaries land together
        // (legacy WriteCdrs + ProcessChargeables + summary write).
        var cdrsWritten = CdrWriter.Write(batch.SummaryStore, rated.ConvertAll(r => r.Cdr), batch.SegmentSize);

        var allChargeables = new List<acc_chargeable>();
        foreach (var r in rated) allChargeables.AddRange(r.Chargeables);
        var chargeablesWritten = ChargeableWriter.Write(batch.SummaryStore, allChargeables, ids, batch.SegmentSize);
        summary.WriteAllChanges(batch.SegmentSize);

        return new CdrBatchResult(rated, unrated, cdrsWritten, chargeablesWritten);
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
    int SegmentSize = BatchSqlWriter.DefaultSegmentSize);

/// <summary>One mediated cdr: the call plus every chargeable its service group's configured rules produced
/// (customer + supplier legs). <see cref="Customer"/> is the customer-leg chargeable the summary reads.</summary>
public sealed record RatedCdr(cdr Cdr, IReadOnlyList<acc_chargeable> Chargeables)
{
    public acc_chargeable? Customer =>
        Chargeables.FirstOrDefault(c => c.assignedDirection == (sbyte)AssignmentDirection.Customer)
        ?? Chargeables.FirstOrDefault();
}

/// <summary>The batch outcome: the rated calls (with chargeables), the cdrs no service group / rate matched,
/// and how many <c>cdr</c> / <c>acc_chargeable</c> rows were written. <see cref="TotalCharged"/> sums the
/// customer billed amounts.</summary>
public sealed record CdrBatchResult(
    IReadOnlyList<RatedCdr> Rated, IReadOnlyList<cdr> Unrated, int CdrsWritten, int ChargeablesWritten)
{
    public int Total => Rated.Count + Unrated.Count;
    public decimal TotalCharged => Rated.Sum(r => r.Customer?.BilledAmount ?? 0m);
}
