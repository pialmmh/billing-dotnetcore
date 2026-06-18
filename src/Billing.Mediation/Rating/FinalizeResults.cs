namespace Billing.Mediation.Rating;

/// <summary>What routesphere reserved for one tier in mem-ledger — the uom tells us package vs cash and
/// the amount is the hold to reconcile against the final charge.</summary>
public sealed record TierReserved(
    long PackageAccountId,
    string Uom,                 // TF_min | OTH_ea | BDT
    decimal ReservedAmount);

/// <summary>One tier's final settlement. routesphere applies <see cref="Charged"/> to mem-ledger
/// (debit final, refund the rest of the hold). For a package uom the charge is in units
/// (<see cref="PackageAmount"/>); for cash (BDT) it is money (<see cref="InPartnerCost"/>).
/// <see cref="Tax"/> is the family's tax (SF10 VAT / SF11 BTRC). A non-null <see cref="Error"/> means
/// this tier could not be settled.</summary>
public sealed record TierSettlement(
    string DbName,
    int PartnerId,
    int ServiceGroupId,
    int ServiceFamilyId,
    string Uom,
    decimal Charged,
    decimal PackageAmount,      // billable minutes for package units (0 for cash)
    decimal InPartnerCost,      // cash cost for BDT (0 for package units)
    decimal Tax,                // family tax (SF10 VAT / SF11 BTRC)
    string MatchedPrefix,
    string? Error)
{
    public static TierSettlement Unrated(string dbName, int partnerId) =>
        new(dbName, partnerId, ServiceGroupId: 0, ServiceFamilyId: 0, Uom: "", Charged: 0, PackageAmount: 0,
            InPartnerCost: 0, Tax: 0, MatchedPrefix: "",
            Error: "unrated: no service group, rate plan, or matching rate");
}

/// <summary>The whole per-call settlement: success + per-tier settlements keyed by dbName (globally
/// unique) + the total charged across tiers.</summary>
public sealed record FinalizeResult(
    bool Success,
    string Error,
    IReadOnlyDictionary<string, TierSettlement> Settlements,
    decimal TotalCharged)
{
    public static FinalizeResult Fail(string error) =>
        new(false, error, new Dictionary<string, TierSettlement>(), 0);
}
