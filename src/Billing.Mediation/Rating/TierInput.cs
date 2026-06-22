using Billing.Mediation.Context;
using Billing.Mediation.Model;

namespace Billing.Mediation.Rating;

/// <summary>Everything the rater needs for ONE tier, extracted from that tier's config snapshot by the
/// caller (so the rater stays free of the tenant-tree / config types). The leaf carries the entry
/// partner; ancestors carry the partner that owns them once the partner hierarchy is wired.</summary>
public sealed record TierInput(
    string DbName,
    int PartnerId,
    MediationContext Mediation,
    IReadOnlyList<PackageAccount> Packages,
    IReadOnlyDictionary<int, Partner> Partners);
