namespace Billing.Mediation.Rating;

public enum ServiceType { Voice, Sms }

/// <summary>The immutable call/SMS facts routesphere sends at admission. No config, no balances —
/// this service derives the tenant chain and rates each tier from its own config snapshot.</summary>
public sealed record CallFacts(
    string Tenant,            // entry tenant dbName (leaf of the chain)
    int PartnerId,            // entry partner
    string CallingNumber,
    string CalledNumber,
    string SourceIp,
    ServiceType ServiceType,
    long StartEpochMillis);
