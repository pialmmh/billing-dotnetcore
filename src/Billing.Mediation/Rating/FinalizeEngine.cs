using Billing.Mediation.Model;
using Billing.Mediation.Summary;
using MediationModel;

namespace Billing.Mediation.Rating;

/// <summary>
/// The post-call charge + summary across the call's tiers — the compute body of <c>FinalizeAndSummarize</c>.
/// Mirrors <see cref="MaxRateEngine"/>: iterate the per-tier inputs, settle each, return the map keyed by
/// dbName. Each tier settles the customer leg (+ the supplier leg for admin <see cref="TierMode.Full"/>)
/// via <see cref="BasicCharge"/>, then — when a summary-store factory is supplied — builds, merges and
/// writes that tier's <c>sum_voice_*</c> via <see cref="CdrSummaryContext"/> (load existing → merge this
/// call → write) on the tier's store. With no factory it stays PURE COMPUTE (no DB), which the unit tests
/// use; the gRPC handler passes a store factory to persist.
/// </summary>
public sealed class FinalizeEngine
{
    private readonly BasicCharge _basicCharge;

    public FinalizeEngine(BasicCharge basicCharge) => _basicCharge = basicCharge;

    public static FinalizeEngine Default() => new(BasicCharge.Default());

    public FinalizeResult Finalize(FinalizeFacts facts, IReadOnlyList<FinalizeTierInput> chain,
        Func<string, ISummaryStore>? summaryStoreFor = null, IAutoIncrementManager? ids = null)
    {
        if (chain.Count == 0)
            return FinalizeResult.Fail($"unknown tenant '{facts.Tenant}'");

        var settlements = new Dictionary<string, TierSettlement>(chain.Count);
        decimal total = 0;
        foreach (var tier in chain)
        {
            var summaryContext = summaryStoreFor is null
                ? null
                : new CdrSummaryContext(summaryStoreFor(tier.DbName), ids ?? new CountingAutoIncrementManager());

            var settlement = SettleTier(facts, tier, summaryContext);
            settlements[tier.DbName] = settlement;
            if (settlement.Error is null) total += settlement.Charged;

            summaryContext?.WriteAllChanges();   // single-connection write into this tier's schema
        }

        var failing = settlements.Values.FirstOrDefault(s => s.Error is not null);
        return new FinalizeResult(failing is null, failing?.Error ?? "", settlements, total);
    }

    private TierSettlement SettleTier(FinalizeFacts facts, FinalizeTierInput tier, CdrSummaryContext? summaryContext)
    {
        var thisCdr = BuildCdr(facts, tier);
        var customer = _basicCharge.Compute(thisCdr, AssignmentDirection.Customer, tier.Mediation, tier.Partners);
        if (customer is null)
            return TierSettlement.Unrated(tier.DbName, tier.PartnerId);

        // Admin (FULL) tiers also charge the supplier leg (the cost paid to the out-partner); reseller
        // tiers are customer-only. The supplier leg reads the InPartnerCost set above, so it runs on the
        // SAME cdr, after the customer leg. (Null when there's no supplier tuple, e.g. SG11.)
        var supplier = tier.Mode == TierMode.Full
            ? _basicCharge.Compute(thisCdr, AssignmentDirection.Supplier, tier.Mediation, tier.Partners)
            : null;
        var supplierCost = supplier?.BilledAmount ?? 0m;

        // Build + merge this call's summary onto the loaded rows (the supplier leg above already wrote the
        // cdr's supplier fields the SG10 summary reads). The caller writes the cache afterward.
        if (summaryContext is not null)
        {
            summaryContext.PopulatePrevSummary(new[] { customer.servicegroup },
                new[] { thisCdr.StartTime.Date }, new[] { HourOf(thisCdr.StartTime) });
            summaryContext.AddCall(thisCdr, customer);
        }

        // The reserved uom decides how the charge lands: package units (consumed minutes) vs cash (BDT).
        var uom = tier.Reserved?.Uom ?? "BDT";
        var isCash = string.Equals(uom, "BDT", StringComparison.OrdinalIgnoreCase);
        var billedAmount = customer.BilledAmount;
        var packageAmount = isCash ? 0m : decimal.Round(customer.Quantity / 60m, 8);
        var inPartnerCost = isCash ? billedAmount : 0m;
        var charged = isCash ? billedAmount : packageAmount;

        return new TierSettlement(tier.DbName, tier.PartnerId, customer.servicegroup, customer.servicefamily,
            uom, charged, packageAmount, inPartnerCost, customer.TaxAmount1 ?? 0m, supplierCost,
            customer.Prefix, Error: null);
    }

    private static DateTime HourOf(DateTime t) => new(t.Year, t.Month, t.Day, t.Hour, 0, 0);

    /// <summary>Build the per-tier cdr from the call facts (dotnet owns the cdr shape, per the contract).
    /// Both numbers are set; the SG detector picks the terminating (out) or originating (in) one. The
    /// charged partner at this tier is the in-partner for the customer leg.</summary>
    private static cdr BuildCdr(FinalizeFacts facts, FinalizeTierInput tier) => new()
    {
        InPartnerId = tier.PartnerId,
        OutPartnerId = facts.OutPartnerId,
        OriginatingCallingNumber = facts.CallingNumber,
        TerminatingCalledNumber = facts.CalledNumber,
        DurationSec = facts.Billsec,
        SwitchId = facts.SwitchId,
        IncomingRoute = facts.IncomingRoute,
        OutgoingRoute = facts.OutgoingRoute,
        AnswerTime = facts.AnswerTime,
        StartTime = facts.AnswerTime,
        ChargingStatus = facts.Answered ? 1 : 0,
    };
}
