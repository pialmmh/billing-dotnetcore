package com.telcobright.billing;

import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import com.telcobright.billing.tenantconfigsync.internal.DayBoundaryRefresher;
import com.telcobright.billing.tenantconfigsync.internal.DebouncedReloader;
import com.telcobright.billing.tenantconfigsync.internal.KafkaConfigEventSource;
import com.telcobright.billing.tenantconfigsync.internal.TenantHierarchyLoader;
import com.telcobright.billing.tenantconfigsync.publishes.ConfigReloadTrigger;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * The tenant-config lifecycle (port of the .NET {@code TenantConfigHostedService}): on start, populate every
 * tenant's DynamicContext (+ MediationContext) from config-manager (FAIL-FAST — a load error stops the boot,
 * exactly like the .NET service), arm the day-boundary refresher, then — only when config-events are enabled —
 * begin listening for change signals; each signal feeds the debounced reloader. With no config-event source,
 * config simply loads once and never reloads (a valid configuration, not an error).
 */
@Singleton
public class BillingBootstrap {
    private static final Logger log = Logger.getLogger(BillingBootstrap.class);

    private final TenantHierarchyLoader _loader;
    private final DebouncedReloader _reloader;
    private final DayBoundaryRefresher _dayRefresher;
    private final TenantConfigSyncOptions _options;
    private final TenantSelection _selection;

    private KafkaConfigEventSource _eventSource;   // started only when config-events are enabled

    @Inject
    public BillingBootstrap(TenantHierarchyLoader loader, DebouncedReloader reloader,
            DayBoundaryRefresher dayRefresher, TenantConfigSyncOptions options, TenantSelection selection) {
        this._loader = loader;
        this._reloader = reloader;
        this._dayRefresher = dayRefresher;
        this._options = options;
        this._selection = selection;
    }

    void onStart(@Observes StartupEvent ev) {
        // fail-fast: load every enabled tenant's hierarchy now (throws -> Quarkus boot fails, like .NET).
        _loader.LoadAll(ConfigReloadTrigger.Startup, null);
        _dayRefresher.Start();

        if (_options.ConfigEvents.Enabled) {
            _eventSource = new KafkaConfigEventSource(_selection, _options.ConfigEvents,
                    Logger.getLogger(KafkaConfigEventSource.class));
            _eventSource.StartAsync(signal -> {
                _reloader.Signal(signal);
                return CompletableFuture.completedFuture(null);
            }, new CompletableFuture<>());
            log.info("tenant config loaded; listening on Kafka config-event source");
        } else {
            log.info("tenant config loaded; no config-event source — reloads disabled");
        }
    }

    @PreDestroy
    void onStop() {
        if (_eventSource != null) _eventSource.StopAsync(new CompletableFuture<>());
        _reloader.Dispose();
        _dayRefresher.Stop();
    }
}
