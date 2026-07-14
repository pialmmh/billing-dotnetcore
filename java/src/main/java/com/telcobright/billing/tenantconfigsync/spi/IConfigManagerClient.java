package com.telcobright.billing.tenantconfigsync.spi;

import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.time.LocalDate;
import java.util.Map;

/**
 * What this package REQUIRES to fetch config: a client to config-manager. The data side of the
 * world — the per-tenant DynamicContext (+ MediationContext) always arrives over HTTP here, never
 * over Kafka. The default HTTP implementation lives in Internal; tests inject a fake.
 *
 * <p>Faithful-port note: the C# {@code Task<Tenant> GetTenantRootAsync(string, CancellationToken)}
 * is ported synchronous (RULE 2 — engine/IO code returns the value directly): the "Async" suffix
 * and the CancellationToken are dropped; per-call timeout is handled inside the HTTP client.
 */
public interface IConfigManagerClient {
    /**
     * Fetch one tenant's root (the nested tree + each node's DynamicContext) from
     * {@code POST /get-specific-tenant-root?name=<tenant>}. Throws
     * {@link ConfigManagerUnavailableException} if config-manager is unreachable, returns an
     * error/empty body, or has no such tenant — fail-fast, because an empty context would silently
     * mis-rate. Never returns null.
     */
    Tenant GetTenantRoot(String tenantName);

    /**
     * On-demand rates for ONE date (planId -> prefix -> served Rate), from
     * {@code POST /get-rates-by-date?name=<tenantDbName>&date=<yyyy-MM-dd>}. The RateCache calls this to
     * back-fill a day it does not hold (back-processing old CDRs); today/tomorrow ride the snapshot instead.
     * Default returns empty so test fakes need not implement it; the HTTP client overrides it.
     */
    default Map<Integer, Map<String, Rate>> GetRatesForDate(String tenantDbName, LocalDate date) {
        return Map.of();
    }
}
