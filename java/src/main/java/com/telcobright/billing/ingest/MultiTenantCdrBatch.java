package com.telcobright.billing.ingest;

import java.util.List;
import java.util.Set;

/**
 * The preprocessor's output for ONE poll-batch (Contract B of {@code docs/cdr-kafka-ingest-contract.md} §3):
 * the batch's cdrs grouped by target {@code tenant} (one {@link PerTenantCdrs} per distinct schema), plus the
 * records that failed decode/validation and must go to the dead-letter path (contract §3.5).
 *
 * <p>{@code unknownTenants} names the tenants of the records that were refused ONLY because the loaded tree does
 * not know them. The tree may simply be behind — a reseller provisioned at run time — so the ingest asks it again
 * before it lets those records be dead letters ({@link UnknownTenantGate}).
 *
 * <p>{@link com.telcobright.billing.ingest.MultiTenantCdrProcessor} consumes {@link #tenants} and writes each tier
 * in its own schema; the Kafka offsets are committed only after the rows and the dead letters.
 */
public record MultiTenantCdrBatch(List<PerTenantCdrs> tenants, List<DeadLetteredCdr> deadLetters, Set<String> unknownTenants) {

    public MultiTenantCdrBatch(List<PerTenantCdrs> tenants, List<DeadLetteredCdr> deadLetters) {
        this(tenants, deadLetters, Set.of());
    }
}
