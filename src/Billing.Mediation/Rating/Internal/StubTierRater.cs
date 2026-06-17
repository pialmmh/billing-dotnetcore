namespace Billing.Mediation.Rating.Internal;

/// <summary>
/// Placeholder rater that proves the traversal + contract end-to-end while the real MediationContext
/// is still being built (Task C, config-manager). It does NOT detect service groups or compute prices;
/// it echoes the tier's eligible package accounts as zero-rated candidates (or one cash placeholder),
/// so the per-tier map carries real shape. Replaced by the Sg*/Sf*/A2ZRater-backed rater once the
/// MediationContext (categories + detection rules + today's rates) lands.
/// </summary>
internal sealed class StubTierRater : ITierRater
{
    public TierRating RateTier(CallFacts facts, TierInput tier)
    {
        var candidates = tier.Packages.Count > 0
            ? tier.Packages.Select(p => new RateCandidate(p.Id, p.Uom ?? "", RatePerMinute: 0d, MaxAmountFirstMinute: 0d)).ToList()
            : [new RateCandidate(PackageAccountId: 0, Uom: "BDT", RatePerMinute: 0d, MaxAmountFirstMinute: 0d)];

        return new TierRating(
            tier.DbName,
            tier.PartnerId,
            ServiceGroupId: 0,        // 0 = not detected yet (stub)
            RejectReason: "",         // stub never rejects; real rater rejects on no-SG / no-rate / no-package
            candidates);
    }
}
