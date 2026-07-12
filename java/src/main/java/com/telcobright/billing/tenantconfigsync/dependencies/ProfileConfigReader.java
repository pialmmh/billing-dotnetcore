package com.telcobright.billing.tenantconfigsync.dependencies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the routesphere-style tenant configuration into the typed options this package runs on.
 *
 * <p><b>Tenant registry</b> (which tenants this instance loads + the active profile per tenant) comes from
 * {@code application.properties} — {@code billing.tenants[i].name|enabled|profile} — mirroring routesphere,
 * where {@code application.properties} only enables/disables a tenant and picks its active profile.
 *
 * <p><b>Per-tenant/per-profile detail</b> lives in {@code config/tenants/<tenant>/<profile>/profile-<profile>.yml}
 * under {@code src/main/resources} (routesphere Tree 1) and is read from the classpath. An external directory
 * can override it via {@code billing.config.dir} (so ops can ship/edit config without a rebuild — the deploy
 * rsync model); the override wins only when the file actually exists there, otherwise the bundled resource is used.
 *
 * <p>kebab-case YAML keys map to PascalCase fields via {@code KEBAB_CASE}; unknown sections
 * (billing.tenant, billing.mediation, …) are ignored. The connection settings (config-manager, config-events)
 * come from the first enabled tenant's profile, since config-manager is the single shared source for every tenant.
 */
public final class ProfileConfigReader {

    private ProfileConfigReader() {
    }

    /** Classpath base for the bundled config tree; also the relative root under any external override dir. */
    static final String ConfigBase = "config";

    private static final ObjectMapper Yaml = new ObjectMapper(new YAMLFactory())
        .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── tenant registry (from application.properties) ───────────────────────────────────────────

    /** Read the tenant registry from the running MP Config (application.properties). */
    public static TenantSelection ReadSelection() {
        return ReadSelection(ConfigProvider.getConfig());
    }

    /**
     * Read the registry from any MP Config: {@code billing.tenants[i].{name,enabled,profile}}, i = 0,1,2…
     * until {@code name} is absent. (Testable overload — pass a built config.)
     */
    public static TenantSelection ReadSelection(Config config) {
        List<SelectedTenant> rows = new ArrayList<>();
        for (int i = 0; ; i++) {
            Optional<String> name = config.getOptionalValue("billing.tenants[" + i + "].name", String.class);
            if (name.isEmpty()) break;
            boolean enabled = config.getOptionalValue("billing.tenants[" + i + "].enabled", Boolean.class).orElse(true);
            String profile = config.getOptionalValue("billing.tenants[" + i + "].profile", String.class).orElse("dev");
            rows.add(new SelectedTenant(name.get(), enabled, profile));
        }
        TenantSelection selection = new TenantSelection();
        selection.Tenants = rows;
        return selection;
    }

    // ── per-profile detail (config/tenants/<t>/<p>/profile-<p>.yml) ──────────────────────────────

    public static TenantConfigSyncOptions ReadOptions(TenantSelection selection) {
        String yaml = loadActiveProfileYaml(selection);
        return yaml == null ? new TenantConfigSyncOptions() : ReadOptionsFromYaml(yaml);
    }

    /**
     * Reads the active profile's billing.datasource block (post-call / batch write slice), including the inline
     * username/password (this project keeps DB creds in the profile YAML, not OpenBao).
     */
    public static DatasourceOptions ReadDatasource(TenantSelection selection) {
        String yaml = loadActiveProfileYaml(selection);
        return yaml == null ? new DatasourceOptions() : ReadDatasourceFromYaml(yaml);
    }

    /**
     * Reads the active profile's billing.summary block (the decoupled outbox hand-off switch).
     * 100% config — there is no environment-variable override.
     */
    public static SummaryOutboxOptions ReadSummary(TenantSelection selection) {
        String yaml = loadActiveProfileYaml(selection);
        return yaml == null ? new SummaryOutboxOptions() : ReadSummaryFromYaml(yaml);
    }

    /** Reads the active profile's billing.cdr-ingest block (the inbound Kafka CDR consumer switch + topic). */
    public static CdrIngestOptions ReadCdrIngest(TenantSelection selection) {
        String yaml = loadActiveProfileYaml(selection);
        return yaml == null ? new CdrIngestOptions() : ReadCdrIngestFromYaml(yaml);
    }

    // ── YAML parsers (package-private, deterministic — unit-tested directly with inline YAML) ────

    static TenantConfigSyncOptions ReadOptionsFromYaml(String yaml) {
        TenantConfigSyncOptions options = new TenantConfigSyncOptions();
        BillingYaml billing = billingOf(yaml);
        if (billing == null) {
            return options;
        }
        if (billing.ConfigManager != null) {
            ConfigManagerYaml cm = billing.ConfigManager;
            options.ConfigManager.BaseUrl = cm.BaseUrl != null ? cm.BaseUrl : options.ConfigManager.BaseUrl;
            options.ConfigManager.TenantRootEndpoint = cm.TenantRootEndpoint != null ? cm.TenantRootEndpoint : options.ConfigManager.TenantRootEndpoint;
            options.ConfigManager.GlobalRegistryEndpoint = cm.GlobalRegistryEndpoint != null ? cm.GlobalRegistryEndpoint : options.ConfigManager.GlobalRegistryEndpoint;
            if (cm.TimeoutSeconds > 0) {
                options.ConfigManager.TimeoutSeconds = cm.TimeoutSeconds;
            }
        }
        if (billing.ConfigEvents != null) {
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

    static DatasourceOptions ReadDatasourceFromYaml(String yaml) {
        DatasourceOptions options = new DatasourceOptions();
        BillingYaml billing = billingOf(yaml);
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

    static SummaryOutboxOptions ReadSummaryFromYaml(String yaml) {
        SummaryOutboxOptions options = new SummaryOutboxOptions();
        BillingYaml billing = billingOf(yaml);
        SummaryYaml s = billing != null ? billing.Summary : null;
        if (s != null) {
            options.Enabled = s.Enabled;
            options.EntityType = s.EntityType != null ? s.EntityType : options.EntityType;
            options.PingTopic = s.PingTopic != null ? s.PingTopic : options.PingTopic;
            options.BootstrapServers = s.BootstrapServers;
        }
        return options;
    }

    static CdrIngestOptions ReadCdrIngestFromYaml(String yaml) {
        CdrIngestOptions options = new CdrIngestOptions();
        BillingYaml billing = billingOf(yaml);
        CdrIngestYaml c = billing != null ? billing.CdrIngest : null;
        if (c != null) {
            options.Enabled = c.Enabled;
            options.BootstrapServers = c.BootstrapServers != null ? c.BootstrapServers : "";
            options.Topic = c.Topic != null ? c.Topic : options.Topic;
            options.ConsumerGroup = c.ConsumerGroup != null ? c.ConsumerGroup : options.ConsumerGroup;
            options.DeadLetterTopic = c.DeadLetterTopic != null ? c.DeadLetterTopic : options.DeadLetterTopic;
            if (c.PollMs > 0) {
                options.PollMs = c.PollMs;
            }
        }
        return options;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** Resolve the active (first enabled) tenant's profile YAML text: external override dir first, then the
     * bundled classpath resource. Returns null if there is no enabled tenant or no profile file. */
    private static String loadActiveProfileYaml(TenantSelection selection) {
        SelectedTenant active = selection.Enabled().stream().findFirst().orElse(null);
        if (active == null) {
            return null;
        }

        // optional external dir (ops edits / deploy rsync) — used only when the file is actually present there.
        Optional<String> overrideDir = ConfigProvider.getConfig().getOptionalValue("billing.config.dir", String.class);
        if (overrideDir.isPresent()) {
            Path p = Path.of(overrideDir.get(), "tenants", active.Name(), active.Profile(),
                "profile-" + active.Profile() + ".yml");
            if (Files.exists(p)) {
                try {
                    return Files.readString(p);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }

        // bundled resource: config/tenants/<name>/<profile>/profile-<profile>.yml
        String resource = ConfigBase + "/tenants/" + active.Name() + "/" + active.Profile()
            + "/profile-" + active.Profile() + ".yml";
        try (InputStream in = ProfileConfigReader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static BillingYaml billingOf(String yaml) {
        ProfileFile pf = readYaml(yaml, ProfileFile.class);
        return (pf != null ? pf : new ProfileFile()).Billing;
    }

    private static <T> T readYaml(String yaml, Class<T> type) {
        try {
            return Yaml.readValue(yaml, type);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    // ── YAML wire shapes (kebab-case via the KEBAB_CASE naming strategy) ─────────────────────────

    static final class ProfileFile {
        public BillingYaml Billing;
    }

    static final class BillingYaml {
        public ConfigManagerYaml ConfigManager;
        public ConfigEventsYaml ConfigEvents;
        public DatasourceYaml Datasource;
        public SummaryYaml Summary;
        public CdrIngestYaml CdrIngest;
    }

    static final class CdrIngestYaml {
        public boolean Enabled;
        public String BootstrapServers;
        public String Topic;
        public String ConsumerGroup;
        public String DeadLetterTopic;
        public int PollMs;
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
