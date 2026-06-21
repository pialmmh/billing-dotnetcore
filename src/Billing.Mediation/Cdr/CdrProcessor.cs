using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.Rating;
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

        // PHASE 1 — Mediate: detect SG → rate through the RateCache → chargeable, per cdr.
        foreach (var thisCdr in batch.Cdrs)
        {
            var customer = _basicCharge.Compute(thisCdr, AssignmentDirection.Customer, batch.Mediation, batch.Partners);
            if (customer is null) { unrated.Add(thisCdr); continue; }

            // The supplier leg (cost paid to the out-partner) runs on the SAME cdr after the customer leg;
            // null when no supplier tuple resolves (e.g. SG11, customer-only).
            var supplier = _basicCharge.Compute(thisCdr, AssignmentDirection.Supplier, batch.Mediation, batch.Partners);
            rated.Add(new RatedCdr(thisCdr, customer, supplier));
        }

        // PHASE 2 — Summaries: load each rated call's prev rows (once per table+bucket) and merge-add it.
        foreach (var r in rated)
        {
            summary.PopulatePrevSummary(new[] { r.Customer.servicegroup }, r.Cdr.StartTime.Date, HourOf(r.Cdr.StartTime));
            summary.AddCall(r.Cdr, r.Customer);
        }

        // PHASE 3 — Write (same single connection): the chargeable rows (customer + supplier legs) then the
        // summaries, so a batch's chargeables + summaries land together.
        var chargeables = new List<acc_chargeable>(rated.Count);
        foreach (var r in rated)
        {
            chargeables.Add(r.Customer);
            if (r.Supplier is not null) chargeables.Add(r.Supplier);
        }
        var chargeablesWritten = ChargeableWriter.Write(batch.SummaryStore, chargeables, ids);
        summary.WriteAllChanges();

        return new CdrBatchResult(rated, unrated, chargeablesWritten);
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
    IAutoIncrementManager? Ids = null);

/// <summary>One mediated cdr: the call plus its customer chargeable and (when present) its supplier leg.</summary>
public sealed record RatedCdr(cdr Cdr, acc_chargeable Customer, acc_chargeable? Supplier);

/// <summary>The batch outcome: the rated calls (with chargeables), the cdrs no service group / rate matched,
/// and how many <c>acc_chargeable</c> rows were written. <see cref="TotalCharged"/> sums the customer billed
/// amounts.</summary>
public sealed record CdrBatchResult(IReadOnlyList<RatedCdr> Rated, IReadOnlyList<cdr> Unrated, int ChargeablesWritten)
{
    public int Total => Rated.Count + Unrated.Count;
    public decimal TotalCharged => Rated.Sum(r => r.Customer.BilledAmount);
}
