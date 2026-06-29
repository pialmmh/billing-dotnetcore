package com.telcobright.billing;

import com.telcobright.billing.data.MySqlCdrBatchRunner;
import com.telcobright.billing.data.MySqlConnectionFactory;
import com.telcobright.billing.mediation.rating.BasicCharge;
import com.telcobright.billing.mediation.rating.FinalizeEngine;
import com.telcobright.billing.mediation.rating.MaxRateEngine;
import com.telcobright.billing.mediation.rating.internal.MaxRateTierRater;
import com.telcobright.billing.tenantconfigsync.dependencies.DatasourceOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.ProfileConfigReader;
import com.telcobright.billing.tenantconfigsync.dependencies.SummaryOutboxOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import com.telcobright.billing.tenantconfigsync.internal.DayBoundaryRefresher;
import com.telcobright.billing.tenantconfigsync.internal.DebouncedReloader;
import com.telcobright.billing.tenantconfigsync.internal.HttpConfigManagerClient;
import com.telcobright.billing.tenantconfigsync.internal.TenantHierarchyLoader;
import com.telcobright.billing.tenantconfigsync.internal.TenantRegistryState;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.http.HttpClient;

/**
 * The composition root — the CDI equivalent of the .NET {@code Program.cs} registrations. Reads the tenant
 * config tree on demand and produces every singleton the beans/handlers inject: the config options, the
 * tenant registry (kept in sync by config-sync), the datasource connection factory + batch runner, and the
 * rating engines (built via the engine's own {@code Default()} factories). The Quarkus gRPC host, the beans
 * (CdrProcessor, SummaryChangeNotificationPublisher), and the handlers are discovered automatically as beans.
 *
 * <p>Secrets (DB user/password) come 100% from the profile YAML inline (this project's convention — not
 * OpenBao, not env). The config directory mirrors the .NET ContentRootPath/config convention.
 */
@ApplicationScoped
public class BillingConfig {

    @ConfigProperty(name = "billing.config.dir", defaultValue = "config")
    String configDir;

    // --- Tenant config (read from the profile YAML on first injection) ----------------------------
    @Produces
    @Singleton
    public TenantSelection tenantSelection() {
        return ProfileConfigReader.ReadSelection(configDir);
    }

    @Produces
    @Singleton
    public TenantConfigSyncOptions tenantConfigSyncOptions(TenantSelection selection) {
        return ProfileConfigReader.ReadOptions(configDir, selection);
    }

    @Produces
    @Singleton
    public DatasourceOptions datasourceOptions(TenantSelection selection) {
        return ProfileConfigReader.ReadDatasource(configDir, selection);
    }

    @Produces
    @Singleton
    public SummaryOutboxOptions summaryOutboxOptions(TenantSelection selection) {
        return ProfileConfigReader.ReadSummary(configDir, selection);
    }

    // --- Tenant registry + the config-sync machinery ----------------------------------------------
    // One TenantRegistryState instance is produced; it is exposed as both ITenantRegistry (the read side
    // the handlers/CdrProcessor use) and as the concrete type the loader writes into.
    @Produces
    @Singleton
    public TenantRegistryState tenantRegistry() {
        return new TenantRegistryState();
    }

    @Produces
    @Singleton
    public IConfigManagerClient configManagerClient(TenantConfigSyncOptions opts) {
        return new HttpConfigManagerClient(HttpClient.newHttpClient(), opts);
    }

    @Produces
    @Singleton
    public TenantHierarchyLoader tenantHierarchyLoader(IConfigManagerClient client, TenantSelection selection,
            TenantRegistryState registry, TenantConfigSyncOptions opts) {
        return new TenantHierarchyLoader(client, selection, registry, opts);
    }

    @Produces
    @Singleton
    public DebouncedReloader debouncedReloader(TenantHierarchyLoader loader, TenantConfigSyncOptions opts) {
        return new DebouncedReloader(loader, opts);
    }

    @Produces
    @Singleton
    public DayBoundaryRefresher dayBoundaryRefresher(TenantHierarchyLoader loader) {
        return new DayBoundaryRefresher(loader);
    }

    // --- Datasource (the post-call / batch write slice) -------------------------------------------
    @Produces
    @Singleton
    public MySqlConnectionFactory connectionFactory(DatasourceOptions ds) {
        return new MySqlConnectionFactory(ds.Host, ds.Port, ds.Username, ds.Password);
    }

    @Produces
    @Singleton
    public MySqlCdrBatchRunner cdrBatchRunner() {
        return MySqlCdrBatchRunner.Default();
    }

    // --- Rating engines (built via the engine's own factories) ------------------------------------
    @Produces
    @Singleton
    public BasicCharge basicCharge() {
        return BasicCharge.Default();
    }

    @Produces
    @Singleton
    public MaxRateEngine maxRateEngine(BasicCharge basicCharge) {
        // GetMaxRatePerMinute (admission): SG detection + RateCache match via BasicCharge, ranked per tier.
        return new MaxRateEngine(new MaxRateTierRater(basicCharge));
    }

    @Produces
    @Singleton
    public FinalizeEngine finalizeEngine(BasicCharge basicCharge) {
        // Finalize (post-call charge): SG detection -> BasicCharge -> FinalizeEngine.
        return new FinalizeEngine(basicCharge);
    }
}
