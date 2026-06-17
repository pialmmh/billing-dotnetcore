namespace Billing.Mediation.Rating;

/// <summary>One ranked option for a tier. routesphere reserves the first FUNDED candidate in
/// mem-ledger. For a package: max_amount_first_minute = 1 unit; for cash: the per-minute rate.</summary>
public sealed record RateCandidate(
    long PackageAccountId,
    string Uom,                 // TF_min | OTH_ea | BDT
    double RatePerMinute,
    double MaxAmountFirstMinute);

/// <summary>One tier's rating: the charged partner, the detected service group (category), the ranked
/// candidates, and a reject reason (non-empty => this tier rejects the call).</summary>
public sealed record TierRating(
    string DbName,
    int PartnerId,
    int ServiceGroupId,
    string RejectReason,
    IReadOnlyList<RateCandidate> Candidates);

/// <summary>The whole multi-tier result: ok + per-tier ratings keyed by dbName (globally unique).</summary>
public sealed record MaxRateResult(
    bool Ok,
    string RejectReason,
    IReadOnlyDictionary<string, TierRating> Tiers)
{
    public static MaxRateResult Reject(string reason) =>
        new(false, reason, new Dictionary<string, TierRating>());
}
