using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Mediation.Rating;

/// <summary>
/// The post-call charge across the call's tiers — the compute body of <c>FinalizeAndSummarize</c>, minus
/// the gRPC mapping, the chain resolution (the caller hands the chain in), and persistence (the
/// single-connection cdr/summary write is a separate, later seam). It mirrors <see cref="MaxRateEngine"/>:
/// iterate the per-tier inputs, settle each, return the map keyed by dbName.
///
/// Each tier currently settles the CUSTOMER leg via <see cref="BasicCharge"/> (detect SG → resolve rate
/// plan tuples → PrefixMatcher → A2ZRater). The <see cref="TierMode.Full"/> admin extras (supplier leg + Sf
/// families + extended AnsCost/BTRC/VAT legs) and the cdr/summary writes are the next slices — so a Full
/// tier currently yields the same customer-leg number as a CustomerOnly tier.
/// </summary>
public sealed class FinalizeEngine
{
    private readonly BasicCharge _basicCharge;

    public FinalizeEngine(BasicCharge basicCharge) => _basicCharge = basicCharge;

    public static FinalizeEngine Default() => new(BasicCharge.Default());

    public FinalizeResult Finalize(FinalizeFacts facts, IReadOnlyList<FinalizeTierInput> chain)
    {
        if (chain.Count == 0)
            return FinalizeResult.Fail($"unknown tenant '{facts.Tenant}'");

        var settlements = new Dictionary<string, TierSettlement>(chain.Count);
        decimal total = 0;
        foreach (var tier in chain)
        {
            var settlement = SettleTier(facts, tier);
            settlements[tier.DbName] = settlement;
            if (settlement.Error is null) total += settlement.Charged;
        }

        var failing = settlements.Values.FirstOrDefault(s => s.Error is not null);
        return new FinalizeResult(failing is null, failing?.Error ?? "", settlements, total);
    }

    private TierSettlement SettleTier(FinalizeFacts facts, FinalizeTierInput tier)
    {
        var thisCdr = BuildCdr(facts, tier);
        var chargeable = _basicCharge.Compute(thisCdr, AssignmentDirection.Customer, tier.Mediation, tier.Partners);
        if (chargeable is null)
            return TierSettlement.Unrated(tier.DbName, tier.PartnerId);

        // The reserved uom decides how the charge lands: package units (consumed minutes) vs cash (BDT).
        var uom = tier.Reserved?.Uom ?? "BDT";
        var isCash = string.Equals(uom, "BDT", StringComparison.OrdinalIgnoreCase);
        var billedAmount = chargeable.BilledAmount;
        var packageAmount = isCash ? 0m : decimal.Round(chargeable.Quantity / 60m, 8);
        var inPartnerCost = isCash ? billedAmount : 0m;
        var charged = isCash ? billedAmount : packageAmount;

        return new TierSettlement(tier.DbName, tier.PartnerId, chargeable.servicegroup, chargeable.servicefamily,
            uom, charged, packageAmount, inPartnerCost, chargeable.TaxAmount1 ?? 0m, chargeable.Prefix, Error: null);
    }

    /// <summary>Build the per-tier cdr from the call facts (dotnet owns the cdr shape, per the contract).
    /// Both numbers are set; the SG detector picks the terminating (out) or originating (in) one. The
    /// charged partner at this tier is the in-partner for the customer leg.</summary>
    private static cdr BuildCdr(FinalizeFacts facts, FinalizeTierInput tier) => new()
    {
        InPartnerId = tier.PartnerId,
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
