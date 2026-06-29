package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * Everything TenantConfigSync needs, injected — never located. Built from a tenant's
 * {@code profile-<profile>.yml} (config-manager + config-events blocks) plus the selected
 * tenant set from {@code tenants.yml}. No secrets live here (code-master rule); the addresses are
 * non-secret and any credential is resolved from the secret store by the adapter that needs it.
 */
public final class TenantConfigSyncOptions {
    /** Root that holds tenants.yml + tenants/&lt;name&gt;/&lt;profile&gt;/… Default: ./config. */
    public String ConfigRoot = "config";

    /** RULE ONE logging: ON → log every tenant/field; OFF → only load/reload/failure events. */
    public boolean DebugMode;

    public ConfigManagerOptions ConfigManager = new ConfigManagerOptions();
    public ConfigEventsOptions ConfigEvents = new ConfigEventsOptions();
}
