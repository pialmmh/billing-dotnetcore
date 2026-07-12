package com.telcobright.billing.ingest;

import java.util.List;

/**
 * The preprocessor's output for ONE poll-batch (Contract B of {@code docs/cdr-kafka-ingest-contract.md} §3):
 * the batch's cdrs grouped by target {@code tenant} (one {@link PerTenantCdrs} per distinct schema), plus the
 * records that failed decode/validation and must go to the dead-letter path (contract §3.5).
 *
 * <p>{@link com.telcobright.billing.ingest.MultiTenantCdrProcessor} (staged next) consumes {@link #tenants}
 * and writes all tiers in ONE cross-schema transaction, committing Kafka offsets only after the DB commit.
 */
public record MultiTenantCdrBatch(List<PerTenantCdrs> tenants, List<DeadLetteredCdr> deadLetters) {
}
