package com.telcobright.billing.tenantconfigsync.dependencies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads the on-disk config tree (tenants.yml + the active profile-&lt;profile&gt;.yml) into the typed
 * options this package runs on. kebab-case YAML keys map to PascalCase props via the hyphenated naming
 * convention; unknown sections (billing.tenant, billing.mediation, …) are ignored. The connection
 * settings (config-manager, config-events) are taken from the first enabled tenant's profile, since
 * config-manager is the single shared source for every tenant.
 *
 * <p>Faithful-port note: YamlDotNet's {@code HyphenatedNamingConvention} + {@code IgnoreUnmatchedProperties}
 * is replaced by Jackson's {@code YAMLFactory} mapper with {@code PropertyNamingStrategies.KEBAB_CASE}
 * (PascalCase field → kebab-case key, e.g. {@code BaseUrl} → {@code base-url}) and
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = false}. The config directory is supplied by the caller as the
 * {@code configRoot} argument (default {@code "config"}); paths are resolved relative to it.
 */
public final class ProfileConfigReader {

    private ProfileConfigReader() {
    }

    private static final ObjectMapper Yaml = new ObjectMapper(new YAMLFactory())
        .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static TenantSelection ReadSelection(String configRoot) {
        Path path = Path.of(configRoot, "tenants.yml");
        if (!Files.exists(path)) {
            return new TenantSelection();
        }

        TenantsFile file = read(path, TenantsFile.class);
        if (file == null) {
            file = new TenantsFile();
        }
        List<TenantRow> src = file.Tenants != null ? file.Tenants : new ArrayList<>();
        List<SelectedTenant> rows = src.stream()
            .map(t -> new SelectedTenant(t.Name != null ? t.Name : "", t.Enabled, t.Profile != null ? t.Profile : "dev"))
            .collect(Collectors.toList());
        TenantSelection selection = new TenantSelection();
        selection.Tenants = rows;
        return selection;
    }

    public static TenantConfigSyncOptions ReadOptions(String configRoot, TenantSelection selection) {
        TenantConfigSyncOptions options = new TenantConfigSyncOptions();
        options.ConfigRoot = configRoot;

        SelectedTenant active = selection.Enabled().stream().findFirst().orElse(null);
        if (active == null) {
            return options;
        }

        Path path = Path.of(configRoot, "tenants", active.Name(), active.Profile(),
            "profile-" + active.Profile() + ".yml");
        if (!Files.exists(path)) {
            return options;
        }

        ProfileFile pf = read(path, ProfileFile.class);
        BillingYaml billing = (pf != null ? pf : new ProfileFile()).Billing;
        if (billing != null && billing.ConfigManager != null) {
            ConfigManagerYaml cm = billing.ConfigManager;
            options.ConfigManager.BaseUrl = cm.BaseUrl != null ? cm.BaseUrl : options.ConfigManager.BaseUrl;
            options.ConfigManager.TenantRootEndpoint = cm.TenantRootEndpoint != null ? cm.TenantRootEndpoint : options.ConfigManager.TenantRootEndpoint;
            options.ConfigManager.GlobalRegistryEndpoint = cm.GlobalRegistryEndpoint != null ? cm.GlobalRegistryEndpoint : options.ConfigManager.GlobalRegistryEndpoint;
            if (cm.TimeoutSeconds > 0) {
                options.ConfigManager.TimeoutSeconds = cm.TimeoutSeconds;
            }
        }
        if (billing != null && billing.ConfigEvents != null) {
            ConfigEventsYaml ce = billing.ConfigEvents;
            options.ConfigEvents.Enabled = ce.Enabled;
            options.ConfigEvents.BootstrapServers = ce.BootstrapServers != null ? ce.BootstrapServers : "";
            options.ConfigEvents.EventTopicBase = ce.EventTopicBase != null ? ce.EventTopicBase : options.ConfigEvents.EventTopicBase;
            options.ConfigEvents.ConsumerGroupBase = ce.ConsumerGroupBase != null ? ce.ConsumerGroupBase : options.ConfigEvents.ConsumerGroupBase;
            if (ce.DebounceMs > 0) {
                options.ConfigEvents.DebounceMs = ce.DebounceMs;
            }
            if (ce.IdleFastPathMultiplier > 0) {
                options.ConfigEvents.IdleFastPathMultiplier = ce.IdleFastPathMultiplier;
            }
        }
        return options;
    }

    /**
     * Reads the active profile's billing.datasource block (post-call / batch write slice),
     * including the inline username/password (this project keeps DB creds in the profile YAML, not OpenBao).
     */
    public static DatasourceOptions ReadDatasource(String configRoot, TenantSelection selection) {
        DatasourceOptions options = new DatasourceOptions();

        SelectedTenant active = selection.Enabled().stream().findFirst().orElse(null);
        if (active == null) {
            return options;
        }

        Path path = Path.of(configRoot, "tenants", active.Name(), active.Profile(),
            "profile-" + active.Profile() + ".yml");
        if (!Files.exists(path)) {
            return options;
        }

        ProfileFile pf = read(path, ProfileFile.class);
        BillingYaml billing = (pf != null ? pf : new ProfileFile()).Billing;
        DatasourceYaml ds = billing != null ? billing.Datasource : null;
        if (ds != null) {
            options.Host = ds.Host != null ? ds.Host : "";
            if (ds.Port > 0) {
                options.Port = ds.Port;
            }
            options.AdminDb = ds.AdminDb != null ? ds.AdminDb : "";
            options.ResellerDbPrefix = ds.ResellerDbPrefix != null ? ds.ResellerDbPrefix : options.ResellerDbPrefix;
            options.Username = ds.Username != null ? ds.Username : "";
            options.Password = ds.Password != null ? ds.Password : "";
        }
        return options;
    }

    /**
     * Reads the active profile's billing.summary block (the decoupled outbox hand-off switch).
     * 100% config — there is no environment-variable override.
     */
    public static SummaryOutboxOptions ReadSummary(String configRoot, TenantSelection selection) {
        SummaryOutboxOptions options = new SummaryOutboxOptions();

        SelectedTenant active = selection.Enabled().stream().findFirst().orElse(null);
        if (active == null) {
            return options;
        }

        Path path = Path.of(configRoot, "tenants", active.Name(), active.Profile(),
            "profile-" + active.Profile() + ".yml");
        if (!Files.exists(path)) {
            return options;
        }

        ProfileFile pf = read(path, ProfileFile.class);
        BillingYaml billing = (pf != null ? pf : new ProfileFile()).Billing;
        SummaryYaml s = billing != null ? billing.Summary : null;
        if (s != null) {
            options.Enabled = s.Enabled;
            options.EntityType = s.EntityType != null ? s.EntityType : options.EntityType;
            options.PingTopic = s.PingTopic != null ? s.PingTopic : options.PingTopic;
            options.BootstrapServers = s.BootstrapServers;
        }
        return options;
    }

    private static <T> T read(Path path, Class<T> type) {
        try {
            return Yaml.readValue(Files.readString(path), type);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // --- YAML wire shapes (kebab-case via the KEBAB_CASE naming strategy) ---

    static final class TenantsFile {
        public List<TenantRow> Tenants;
    }

    static final class TenantRow {
        public String Name;
        public boolean Enabled;
        public String Profile;
    }

    static final class ProfileFile {
        public BillingYaml Billing;
    }

    static final class BillingYaml {
        public ConfigManagerYaml ConfigManager;
        public ConfigEventsYaml ConfigEvents;
        public DatasourceYaml Datasource;
        public SummaryYaml Summary;
    }

    static final class DatasourceYaml {
        public String Host;
        public int Port;
        public String AdminDb;
        public String ResellerDbPrefix;
        public String Username;
        public String Password;
    }

    static final class SummaryYaml {
        public boolean Enabled;
        public String EntityType;
        public String PingTopic;
        public String BootstrapServers;
    }

    static final class ConfigManagerYaml {
        public String BaseUrl;
        public String TenantRootEndpoint;
        public String GlobalRegistryEndpoint;
        public int TimeoutSeconds;
    }

    static final class ConfigEventsYaml {
        public boolean Enabled;
        public String BootstrapServers;
        public String EventTopicBase;
        public String ConsumerGroupBase;
        public int DebounceMs;
        public int IdleFastPathMultiplier;
    }
}
