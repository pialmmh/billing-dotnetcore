package com.telcobright.billing.ingest;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.List;

/**
 * One tenant's slice of a preprocessed poll-batch (Contract B of {@code docs/cdr-kafka-ingest-contract.md} §3):
 * the target {@code tenant} schema, that tenant's resolved {@link Tenant} config (its
 * {@code Context.MediationContext} + {@code Context.Partners}), and the shaped engine {@code cdr}s to run
 * through the single-tenant {@link com.telcobright.billing.mediation.cdr.CdrPipeline} for this tier.
 */
public record PerTenantCdrs(String tenant, Tenant context, List<cdr> cdrs) {
}
