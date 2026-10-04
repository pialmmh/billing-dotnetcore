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

    /** Reads the active profile's billing.summary-rollup block (the outbox -> sum_voice consumer switch). */
    public static SummaryRollupOptions ReadSummaryRollup(TenantSelection selection) {
        String yaml = loadActiveProfileYaml(selection);
        return yaml == null ? new SummaryRollupOptions() : ReadSummaryRollupFromYaml(yaml);
    }

    /** Reads the active profile's billing.mediation block — currently the fixed switch id (source NE's
     * idSwitch) stamped onto every ingested cdr. */
    public static MediationOptions ReadMediation(TenantSelection selection) {
        String yaml = loadActiveProfileYaml(selection);
        return yaml == null ? new MediationOptions() : ReadMediationFromYaml(yaml);
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
            options.Kind = DatasourceKindOf(ds.Kind);
            options.Host = ds.Host != null ? ds.Host : "";
            options.Port = ds.Port > 0 ? ds.Port : (options.IsPostgres() ? 5432 : options.Port);
            options.Database = ds.Database != null ? ds.Database : "";
            ReadPostgresBlock(ds.Postgres, options);
            options.AdminDb = ds.AdminDb != null ? ds.AdminDb : "";
            options.ResellerDbPrefix = ds.ResellerDbPrefix != null ? ds.ResellerDbPrefix : options.ResellerDbPrefix;
            options.Username = ds.Username != null ? ds.Username : "";
            options.Password = ds.Password != null ? ds.Password : "";
            options.PasswordRef = ds.PasswordRef != null ? ds.PasswordRef : "";
        }
        return options;
    }

    /** {@code mysql} (also when the key is absent) or {@code postgresql}; any other word is a refusal at start. */
    private static String DatasourceKindOf(String value) {
        if (value == null || value.isBlank()) return DatasourceOptions.MySql;
        String word = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!word.equals(DatasourceOptions.MySql) && !word.equals(DatasourceOptions.PostgreSql)) {
            throw new IllegalStateException("billing.datasource.kind must be 'mysql' or 'postgresql', not '" + value + "'");
        }
        return word;
    }

    /** {@code billing.datasource.postgres}: an absent key keeps its default; an empty role / an empty list switches
     * that grant off. */
    private static void ReadPostgresBlock(PostgresYaml pg, DatasourceOptions options) {
        if (pg == null) return;
        if (pg.SummaryServiceRole != null) options.PostgresSummaryServiceRole = pg.SummaryServiceRole.trim();
        if (pg.ReaderRoles != null) options.PostgresReaderRoles = List.copyOf(pg.ReaderRoles);
        if (pg.MonthsBack != null) options.PostgresMonthsBack = pg.MonthsBack;
        if (pg.MonthsAhead != null) options.PostgresMonthsAhead = pg.MonthsAhead;
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
            options.LegacyDedupEnabled = c.LegacyDedupEnabled;   // cutover switch; absent in yaml → false (unchanged)
            if (c.AutoOffsetReset != null && !c.AutoOffsetReset.isBlank()) {
                options.AutoOffsetReset = OffsetResetOf(c.AutoOffsetReset);
            }
            if (c.DeadLetterUnhealthyAfterTries > 0) {
                options.DeadLetterUnhealthyAfterTries = c.DeadLetterUnhealthyAfterTries;
            }
            if (c.UnknownTenantReloadSeconds > 0) {
                options.UnknownTenantReloadSeconds = c.UnknownTenantReloadSeconds;
            }
        }
        return options;
    }

    /** {@code earliest} or {@code latest}; any other word is a refusal at start, not a silent default. */
    private static String OffsetResetOf(String value) {
        String word = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!word.equals("earliest") && !word.equals("latest")) {
            throw new IllegalStateException("billing.cdr-ingest.auto-offset-reset must be 'earliest' or 'latest', not '"
                + value + "'");
        }
        return word;
    }

    static SummaryRollupOptions ReadSummaryRollupFromYaml(String yaml) {
        SummaryRollupOptions options = new SummaryRollupOptions();
        BillingYaml billing = billingOf(yaml);
        SummaryRollupYaml s = billing != null ? billing.SummaryRollup : null;
        if (s != null) {
            options.Enabled = s.Enabled;
            options.EntityType = s.EntityType != null ? s.EntityType : options.EntityType;
            if (s.PollMs > 0) {
                options.PollMs = s.PollMs;
            }
            if (s.MaxRowsPerPoll > 0) {
                options.MaxRowsPerPoll = s.MaxRowsPerPoll;
            }
            if (s.SegmentSize > 0) {
                options.SegmentSize = s.SegmentSize;
            }
        }
        return options;
    }

    static MediationOptions ReadMediationFromYaml(String yaml) {
        MediationOptions options = new MediationOptions();
        BillingYaml billing = billingOf(yaml);
        MediationYaml m = billing != null ? billing.Mediation : null;
        if (m != null && m.SwitchId > 0) {
            options.SwitchId = m.SwitchId;
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
        Path file = ExternalProfileFile(active);
        if (file != null) {
            try {
                return Files.readString(file);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        // bundled resource: config/tenants/<name>/<profile>/profile-<profile>.yml
        try (InputStream in = ProfileConfigReader.class.getClassLoader().getResourceAsStream(BundledProfileResource(active))) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The active tenant's profile file under {@code billing.config.dir} — when that key is set and the file is
     * there; else null (the jar's own resource is then read). */
    private static Path ExternalProfileFile(SelectedTenant active) {
        Optional<String> overrideDir = ConfigProvider.getConfig().getOptionalValue("billing.config.dir", String.class);
        if (overrideDir.isEmpty()) {
            return null;
        }
        Path p = Path.of(overrideDir.get(), "tenants", active.Name(), active.Profile(),
            "profile-" + active.Profile() + ".yml");
        return Files.exists(p) ? p : null;
    }

    private static String BundledProfileResource(SelectedTenant active) {
        return ConfigBase + "/tenants/" + active.Name() + "/" + active.Profile()
            + "/profile-" + active.Profile() + ".yml";
    }

    /**
     * Where the active (first enabled) tenant's profile is read from, in words — the first line of a start's log
     * ({@code StartEndpoints}). A start that fell back to a profile THE JAR carries is then seen for what it is.
     */
    public static String ActiveProfileSource(TenantSelection selection) {
        SelectedTenant active = selection.Enabled().stream().findFirst().orElse(null);
        if (active == null) {
            return "no tenant is enabled: the built-in defaults";
        }
        String who = "tenant " + active.Name() + ", profile " + active.Profile() + ": ";
        Path file = ExternalProfileFile(active);
        if (file != null) {
            return who + "the file " + file.toAbsolutePath().normalize();
        }
        String resource = BundledProfileResource(active);
        boolean theJarHasOne = ProfileConfigReader.class.getClassLoader().getResource(resource) != null;
        String configDir = ConfigProvider.getConfig().getOptionalValue("billing.config.dir", String.class).orElse(null);
        return who + (theJarHasOne
            ? "THE JAR'S OWN " + resource + (configDir == null ? " (billing.config.dir is not set)"
                : " (no such file under billing.config.dir = " + configDir + ")")
            : "no profile file anywhere: the built-in defaults");
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
        public SummaryRollupYaml SummaryRollup;
        public MediationYaml Mediation;
    }

    static final class MediationYaml {
        public int SwitchId;
    }

    static final class CdrIngestYaml {
        public boolean Enabled;
        public String BootstrapServers;
        public String Topic;
        public String ConsumerGroup;
        public String DeadLetterTopic;
        public int PollMs;
        public boolean LegacyDedupEnabled;   // cutover switch (billing.cdr-ingest.legacy-dedup-enabled)
        public String AutoOffsetReset;       // earliest (default) | latest
        public int DeadLetterUnhealthyAfterTries;
        public int UnknownTenantReloadSeconds;
    }

    static final class SummaryRollupYaml {
        public boolean Enabled;
        public String EntityType;
        public int PollMs;
        public int MaxRowsPerPoll;
        public int SegmentSize;
    }

    static final class PostgresYaml {
        public String SummaryServiceRole;
        public List<String> ReaderRoles;
        public Integer MonthsBack;
        public Integer MonthsAhead;
    }

    static final class DatasourceYaml {
        public String Kind;
        public String Database;
        public PostgresYaml Postgres;
        public String Host;
        public int Port;
        public String AdminDb;
        public String ResellerDbPrefix;
        public String Username;
        public String Password;
        public String PasswordRef;           // env:<VAR> — the name of the variable that holds the password
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
