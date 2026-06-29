package com.telcobright.billing.mediation.rating;

/**
 * The immutable call/SMS facts routesphere sends at admission. No config, no balances —
 * this service derives the tenant chain and rates each tier from its own config snapshot.
 */
public record CallFacts(
        String Tenant,            // entry tenant dbName (leaf of the chain)
        int PartnerId,            // entry partner
        String CallingNumber,
        String CalledNumber,
        String SourceIp,
        ServiceType ServiceType,
        long StartEpochMillis) {
}
