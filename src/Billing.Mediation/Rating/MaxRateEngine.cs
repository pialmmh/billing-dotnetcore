namespace Billing.Mediation.Rating;

/// <summary>
/// Traverses the entry tenant's chain (leaf→root) and rates each tier, returning the per-tier map
/// keyed by dbName. This is the body of GetMaxRatePerMinute, minus the gRPC and the registry lookup
/// (the caller resolves the chain and hands it in). Per-tier rating is delegated to <see cref="ITierRater"/>.
/// </summary>
public sealed class MaxRateEngine
{
    private readonly ITierRater _rater;

    public MaxRateEngine(ITierRater rater) => _rater = rater;

    public MaxRateResult Resolve(CallFacts facts, IReadOnlyList<TierInput> chain)
    {
        if (chain.Count == 0)
            return MaxRateResult.Reject($"unknown tenant '{facts.Tenant}'");

        var tiers = new Dictionary<string, TierRating>(chain.Count);
        foreach (var tier in chain)
            tiers[tier.DbName] = _rater.RateTier(facts, tier);

        var rejecting = tiers.Values.FirstOrDefault(t => !string.IsNullOrEmpty(t.RejectReason));
        return new MaxRateResult(rejecting is null, rejecting?.RejectReason ?? "", tiers);
    }
}
