package com.telcobright.billing.tenantconfigsync.dependencies;

public final class ConfigManagerOptions {
    public String BaseUrl = "http://localhost:7072";
    public String TenantRootEndpoint = "/get-specific-tenant-root";
    /** On-demand rates for one date — the RateCache back-fills older days (back-processing) from here. */
    public String RatesByDateEndpoint = "/get-rates-by-date";
    public String GlobalRegistryEndpoint = "/get-global-tenant-registry";
    public int TimeoutSeconds = 30;
    /** How many distinct days each tenant's RateCache keeps before a flush-all (realtime keeps today+tomorrow). */
    public int RateCacheMaxDays = 7;
}
