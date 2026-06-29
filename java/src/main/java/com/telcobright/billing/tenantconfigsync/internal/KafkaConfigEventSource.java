package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.dependencies.ConfigEventsOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import com.telcobright.billing.tenantconfigsync.spi.ConfigChangeSignal;
import com.telcobright.billing.tenantconfigsync.spi.IConfigEventSource;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The host-side Kafka adapter implementing {@link IConfigEventSource} — config-sync infrastructure, NOT a
 * bean: it listens to routesphere's {@code config_event_loader_<tenant>} topics and raises a
 * {@link ConfigChangeSignal} per "tenant X changed" message (never the config itself). A THIN adapter —
 * construction + start/stop the listen-loop; the consume machinery is in {@link ConfigEventConsumerLoop}.
 * Constructed by the host wiring (BillingBootstrap) only when config-events are enabled.
 */
public final class KafkaConfigEventSource implements IConfigEventSource {
    private final TenantSelection _selection;
    private final ConfigEventsOptions _opts;
    private final Logger _log;

    private ConfigEventConsumerLoop _loop;

    public KafkaConfigEventSource(TenantSelection selection, ConfigEventsOptions opts, Logger log) {
        this._selection = selection;
        this._opts = opts;
        this._log = log;
    }

    /** Start listening: subscribe to the enabled tenants' topics and poll in the background. */
    @Override
    public CompletableFuture<Void> StartAsync(Function<ConfigChangeSignal, CompletableFuture<Void>> onSignal,
            CompletableFuture<Void> ct) {
        _loop = ConfigEventConsumerLoop.Start(_selection, _opts, _log, onSignal);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> StopAsync(CompletableFuture<Void> ct) {
        if (_loop != null) _loop.stop();
        return CompletableFuture.completedFuture(null);
    }
}
