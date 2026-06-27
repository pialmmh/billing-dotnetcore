using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace Billing.Config.TenantConfigSync.Dependencies;

/// <summary>
/// Reads the on-disk config tree (tenants.yml + the active profile-&lt;profile&gt;.yml) into the typed
/// options this package runs on. kebab-case YAML keys map to PascalCase props via the hyphenated naming
/// convention; unknown sections (billing.tenant, billing.mediation, …) are ignored. The connection
/// settings (config-manager, config-events) are taken from the first enabled tenant's profile, since
/// config-manager is the single shared source for every tenant.
/// </summary>
public static class ProfileConfigReader
{
    private static readonly IDeserializer Yaml = new DeserializerBuilder()
        .WithNamingConvention(HyphenatedNamingConvention.Instance)
        .IgnoreUnmatchedProperties()
        .Build();

    public static TenantSelection ReadSelection(string configRoot)
    {
        var path = Path.Combine(configRoot, "tenants.yml");
        if (!File.Exists(path)) return new TenantSelection();

        var file = Yaml.Deserialize<TenantsFile>(File.ReadAllText(path)) ?? new TenantsFile();
        var rows = (file.Tenants ?? [])
            .Select(t => new SelectedTenant(t.Name ?? "", t.Enabled, t.Profile ?? "dev"))
            .ToList();
        return new TenantSelection { Tenants = rows };
    }

    public static TenantConfigSyncOptions ReadOptions(string configRoot, TenantSelection selection)
    {
        var options = new TenantConfigSyncOptions { ConfigRoot = configRoot };

        var active = selection.Enabled.FirstOrDefault();
        if (active is null) return options;

        var path = Path.Combine(configRoot, "tenants", active.Name, active.Profile,
            $"profile-{active.Profile}.yml");
        if (!File.Exists(path)) return options;

        var billing = (Yaml.Deserialize<ProfileFile>(File.ReadAllText(path)) ?? new ProfileFile()).Billing;
        if (billing?.ConfigManager is { } cm)
        {
            options.ConfigManager.BaseUrl = cm.BaseUrl ?? options.ConfigManager.BaseUrl;
            options.ConfigManager.TenantRootEndpoint = cm.TenantRootEndpoint ?? options.ConfigManager.TenantRootEndpoint;
            options.ConfigManager.GlobalRegistryEndpoint = cm.GlobalRegistryEndpoint ?? options.ConfigManager.GlobalRegistryEndpoint;
            if (cm.TimeoutSeconds > 0) options.ConfigManager.TimeoutSeconds = cm.TimeoutSeconds;
        }
        if (billing?.ConfigEvents is { } ce)
        {
            options.ConfigEvents.Enabled = ce.Enabled;
            options.ConfigEvents.BootstrapServers = ce.BootstrapServers ?? "";
            options.ConfigEvents.EventTopicBase = ce.EventTopicBase ?? options.ConfigEvents.EventTopicBase;
            options.ConfigEvents.ConsumerGroupBase = ce.ConsumerGroupBase ?? options.ConfigEvents.ConsumerGroupBase;
            if (ce.DebounceMs > 0) options.ConfigEvents.DebounceMs = ce.DebounceMs;
            if (ce.IdleFastPathMultiplier > 0) options.ConfigEvents.IdleFastPathMultiplier = ce.IdleFastPathMultiplier;
        }
        return options;
    }

    /// <summary>Reads the active profile's billing.datasource block (post-call / batch write slice),
    /// including the inline username/password (this project keeps DB creds in the profile YAML, not OpenBao).</summary>
    public static DatasourceOptions ReadDatasource(string configRoot, TenantSelection selection)
    {
        var options = new DatasourceOptions();

        var active = selection.Enabled.FirstOrDefault();
        if (active is null) return options;

        var path = Path.Combine(configRoot, "tenants", active.Name, active.Profile,
            $"profile-{active.Profile}.yml");
        if (!File.Exists(path)) return options;

        var ds = (Yaml.Deserialize<ProfileFile>(File.ReadAllText(path)) ?? new ProfileFile()).Billing?.Datasource;
        if (ds is not null)
        {
            options.Host = ds.Host ?? "";
            if (ds.Port > 0) options.Port = ds.Port;
            options.AdminDb = ds.AdminDb ?? "";
            options.ResellerDbPrefix = ds.ResellerDbPrefix ?? options.ResellerDbPrefix;
            options.Username = ds.Username ?? "";
            options.Password = ds.Password ?? "";
        }
        return options;
    }

    /// <summary>Reads the active profile's billing.summary block (the decoupled outbox hand-off switch).
    /// 100% config — there is no environment-variable override.</summary>
    public static SummaryOutboxOptions ReadSummary(string configRoot, TenantSelection selection)
    {
        var options = new SummaryOutboxOptions();

        var active = selection.Enabled.FirstOrDefault();
        if (active is null) return options;

        var path = Path.Combine(configRoot, "tenants", active.Name, active.Profile,
            $"profile-{active.Profile}.yml");
        if (!File.Exists(path)) return options;

        var s = (Yaml.Deserialize<ProfileFile>(File.ReadAllText(path)) ?? new ProfileFile()).Billing?.Summary;
        if (s is not null)
        {
            options.Enabled = s.Enabled;
            options.EntityType = s.EntityType ?? options.EntityType;
            options.PingTopic = s.PingTopic ?? options.PingTopic;
            options.BootstrapServers = s.BootstrapServers;
        }
        return options;
    }

    // --- YAML wire shapes (kebab-case via hyphenated naming convention) ---

    private sealed class TenantsFile { public List<TenantRow>? Tenants { get; set; } }
    private sealed class TenantRow { public string? Name { get; set; } public bool Enabled { get; set; } public string? Profile { get; set; } }

    private sealed class ProfileFile { public BillingYaml? Billing { get; set; } }
    private sealed class BillingYaml
    {
        public ConfigManagerYaml? ConfigManager { get; set; }
        public ConfigEventsYaml? ConfigEvents { get; set; }
        public DatasourceYaml? Datasource { get; set; }
        public SummaryYaml? Summary { get; set; }
    }
    private sealed class DatasourceYaml
    {
        public string? Host { get; set; }
        public int Port { get; set; }
        public string? AdminDb { get; set; }
        public string? ResellerDbPrefix { get; set; }
        public string? Username { get; set; }
        public string? Password { get; set; }
    }
    private sealed class SummaryYaml
    {
        public bool Enabled { get; set; }
        public string? EntityType { get; set; }
        public string? PingTopic { get; set; }
        public string? BootstrapServers { get; set; }
    }
    private sealed class ConfigManagerYaml
    {
        public string? BaseUrl { get; set; }
        public string? TenantRootEndpoint { get; set; }
        public string? GlobalRegistryEndpoint { get; set; }
        public int TimeoutSeconds { get; set; }
    }
    private sealed class ConfigEventsYaml
    {
        public bool Enabled { get; set; }
        public string? BootstrapServers { get; set; }
        public string? EventTopicBase { get; set; }
        public string? ConsumerGroupBase { get; set; }
        public int DebounceMs { get; set; }
        public int IdleFastPathMultiplier { get; set; }
    }
}
