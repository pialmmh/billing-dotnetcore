namespace Billing.Config.TenantConfigSync.Dependencies;

/// <summary>
/// Everything TenantConfigSync needs, injected — never located. Built from a tenant's
/// <c>profile-&lt;profile&gt;.yml</c> (config-manager + config-events blocks) plus the selected
/// tenant set from <c>tenants.yml</c>. No secrets live here (code-master rule); the addresses are
/// non-secret and any credential is resolved from the secret store by the adapter that needs it.
/// </summary>
public sealed class TenantConfigSyncOptions
{
    /// <summary>Root that holds tenants.yml + tenants/&lt;name&gt;/&lt;profile&gt;/… Default: ./config.</summary>
    public string ConfigRoot { get; set; } = "config";

    /// <summary>RULE ONE logging: ON → log every tenant/field; OFF → only load/reload/failure events.</summary>
    public bool DebugMode { get; set; }

    public ConfigManagerOptions ConfigManager { get; set; } = new();
    public ConfigEventsOptions ConfigEvents { get; set; } = new();
}

public sealed class ConfigManagerOptions
{
    public string BaseUrl { get; set; } = "http://localhost:7072";
    public string TenantRootEndpoint { get; set; } = "/get-specific-tenant-root";
    public string GlobalRegistryEndpoint { get; set; } = "/get-global-tenant-registry";
    public int TimeoutSeconds { get; set; } = 30;
}

public sealed class ConfigEventsOptions
{
    public bool Enabled { get; set; }
    public string BootstrapServers { get; set; } = "";
    public string EventTopicBase { get; set; } = "config_event_loader";
    public string ConsumerGroupBase { get; set; } = "billing-core-config-reload";
    public int DebounceMs { get; set; } = 3000;

    /// <summary>Idle longer than DebounceMs × this → reload immediately (leading-edge fast path).</summary>
    public int IdleFastPathMultiplier { get; set; } = 2;
}
