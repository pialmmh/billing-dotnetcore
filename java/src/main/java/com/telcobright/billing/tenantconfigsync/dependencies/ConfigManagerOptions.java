package com.telcobright.billing.tenantconfigsync.dependencies;

public final class ConfigManagerOptions {
    public String BaseUrl = "http://localhost:7072";
    public String TenantRootEndpoint = "/get-specific-tenant-root";
    public String GlobalRegistryEndpoint = "/get-global-tenant-registry";
    public int TimeoutSeconds = 30;
}
