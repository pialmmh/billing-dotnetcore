using Billing.Mediation.Context;
using Billing.Mediation.Model;

namespace Billing.Mediation.Rating;

/// <summary>The post-call facts the finalize engine rates from — proto-agnostic, the way
/// <see cref="CallFacts"/> is for admission. The caller resolves the tenant chain and hands in the
/// per-tier inputs; the engine only rates.</summary>
public sealed record FinalizeFacts(
    string Tenant,            // entry tenant dbName (for error messages; the chain is passed in)
    string CallingNumber,
    string CalledNumber,
    ServiceType ServiceType,
    int SwitchId,
    string IncomingRoute,
    string OutgoingRoute,
    int Billsec,              // answered/billable seconds (0 = unanswered → zero charge)
    bool Answered,
    string UniqueId);         // routesphere's call id — the idempotency key for the (future) persistence

/// <summary>Per-tier charge mode (the architect's locked contract): the admin/operator tier settles the
/// FULL chain (customer + supplier + families); a reseller tier settles the CUSTOMER leg only.</summary>
public enum TierMode { CustomerOnly, Full }

/// <summary>Everything the finalize engine needs for ONE tier, extracted from that tier's config snapshot
/// by the caller (so the engine stays free of the tenant-tree/config types). <see cref="Partners"/> backs
/// service-group detection; <see cref="Reserved"/> is what routesphere held (package vs cash + amount to
/// reconcile).</summary>
public sealed record FinalizeTierInput(
    string DbName,
    int PartnerId,
    MediationContext Mediation,
    IReadOnlyDictionary<int, Partner> Partners,
    TierMode Mode,
    TierReserved? Reserved);
