using MediationModel;

namespace Billing.Mediation.Rating.Internal;

/// <summary>
/// The real per-tier rater for <c>GetMaxRatePerMinute</c> (admission): detect the service group from the
/// tier's <c>MediationContext</c> + the called number, match the CUSTOMER rate over the per-day RateCache
/// (reusing <see cref="BasicCharge.MatchCustomerRate"/>), and return the ranked candidates — a CASH candidate
/// carrying the per-minute rate + the first-minute max, plus the tier's eligible PACKAGE accounts (consumed
/// at 1 unit / minute). Rejects when no service group is detected, or when neither a rate nor a package is
/// available. routesphere reserves the first FUNDED candidate per tier.
/// </summary>
internal sealed class MaxRateTierRater : ITierRater
{
    private readonly BasicCharge _basicCharge;

    public MaxRateTierRater(BasicCharge basicCharge) => _basicCharge = basicCharge;

    public TierRating RateTier(CallFacts facts, TierInput tier)
    {
        var start = DateTimeOffset.FromUnixTimeMilliseconds(facts.StartEpochMillis).UtcDateTime;
        var thisCdr = new cdr
        {
            InPartnerId = tier.PartnerId,
            OriginatingCallingNumber = facts.CallingNumber,
            TerminatingCalledNumber = facts.CalledNumber,
            StartTime = start,
            AnswerTime = start,
            DurationSec = 60m,
        };

        var (serviceGroupId, rate) = _basicCharge.MatchCustomerRate(thisCdr, tier.Mediation, tier.Partners);
        if (serviceGroupId == 0)
            return new TierRating(tier.DbName, tier.PartnerId, 0, "service group not detected", []);

        var candidates = new List<RateCandidate>(tier.Packages.Count + 1);

        // package candidates — consumed at 1 unit / minute (the cash rate doesn't apply to package units).
        foreach (var p in tier.Packages)
            candidates.Add(new RateCandidate(p.Id, p.Uom ?? "", RatePerMinute: 0d, MaxAmountFirstMinute: 1d));

        // cash candidate — the matched per-minute rate + the first-minute max (faithful A2Z over the first 60s).
        if (rate is not null)
        {
            var firstMinute = A2ZRater.Rate(rate, 60m, 60, 8).Amount;
            candidates.Add(new RateCandidate(0, "BDT", (double)rate.rateamount, (double)firstMinute));
        }

        return candidates.Count == 0
            ? new TierRating(tier.DbName, tier.PartnerId, serviceGroupId, "no rate or package for the call", [])
            : new TierRating(tier.DbName, tier.PartnerId, serviceGroupId, "", candidates);
    }
}
