package com.telcobright.billing.mediation.rating;

import java.time.LocalDateTime;

/**
 * The post-call facts the finalize engine rates from — proto-agnostic, the way
 * {@link CallFacts} is for admission. The caller resolves the tenant chain and hands in the
 * per-tier inputs; the engine only rates.
 */
public record FinalizeFacts(
        String Tenant,            // entry tenant dbName (for error messages; the chain is passed in)
        String CallingNumber,
        String CalledNumber,
        ServiceType ServiceType,
        int SwitchId,
        String IncomingRoute,
        String OutgoingRoute,
        int OutPartnerId,         // the routed out-partner/carrier — the supplier leg (admin FULL) keys off it
        LocalDateTime AnswerTime, // when the call answered — drives the rate's date-validity match
        int Billsec,              // answered/billable seconds (0 = unanswered -> zero charge)
        boolean Answered,
        String UniqueId) {        // routesphere's call id — the idempotency key for the (future) persistence
}
