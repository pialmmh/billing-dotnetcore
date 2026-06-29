package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.tenantconfigsync.dependencies.SelectedTenant;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.tenantconfigsync.publishes.ConfigReloadTrigger;
import com.telcobright.billing.tenantconfigsync.publishes.ConfigReloadedEvent;
import com.telcobright.billing.tenantconfigsync.spi.ConfigManagerUnavailableException;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Builds the whole tenant tree from config-manager and swaps it into the registry. Used both for the
 * one-time start-up load and for every config-event reload — same path, different trigger. One load
 * runs at a time (a reload can fire while start-up is still in flight); the gate serialises them.
 *
 * <p>Faithful-port note: C# {@code Task<ConfigReloadedEvent> LoadAllAsync(..., CancellationToken)} is
 * ported synchronous (RULE 2) as {@link #LoadAll}; the {@code SemaphoreSlim(1,1)} gate → a
 * {@link ReentrantLock}. The C# {@code event EventHandler<ConfigReloadedEvent> Reloaded} → a listener
 * list with {@link #addReloadedListener}/{@link #removeReloadedListener}. {@code ILogger}/{@code IOptions}
 * injection is dropped (static logger; options passed directly). C# {@code internal} → Java {@code public}.
 */
public final class TenantHierarchyLoader {
    private static final Logger log = Logger.getLogger(TenantHierarchyLoader.class);

    private final IConfigManagerClient _client;
    private final TenantSelection _selection;
    private final TenantRegistryState _registry;
    private final TenantConfigSyncOptions _opts;
    private final ReentrantLock _gate = new ReentrantLock();

    /** Raised after each successful swap (metrics / cache warm-up can subscribe). */
    private final List<BiConsumer<Object, ConfigReloadedEvent>> Reloaded = new CopyOnWriteArrayList<>();

    public TenantHierarchyLoader(IConfigManagerClient client, TenantSelection selection,
        TenantRegistryState registry, TenantConfigSyncOptions opts) {
        _client = client;
        _selection = selection;
        _registry = registry;
        _opts = opts;
    }

    public void addReloadedListener(BiConsumer<Object, ConfigReloadedEvent> handler) {
        Reloaded.add(handler);
    }

    public void removeReloadedListener(BiConsumer<Object, ConfigReloadedEvent> handler) {
        Reloaded.remove(handler);
    }

    public ConfigReloadedEvent LoadAll(ConfigReloadTrigger trigger, String eventId) {
        _gate.lock();
        try {
            long sw = System.nanoTime();
            List<Tenant> roots = new ArrayList<>();

            for (SelectedTenant sel : _selection.Enabled()) {
                Tenant tenant;
                try {
                    tenant = _client.GetTenantRoot(sel.Name());
                } catch (ConfigManagerUnavailableException ex) {
                    // Fail-fast: refuse to serve an empty/partial context (it would silently mis-rate).
                    // Nothing is swapped — on startup the app fails to start; on reload the last-good
                    // snapshot keeps serving. Loud, then propagate.
                    log.errorf(ex,
                        "config %s ABORTED — config-manager unavailable for tenant %s; "
                            + "registry NOT swapped (keeping last-good config)", trigger, sel.Name());
                    throw ex;
                }

                if (_opts.DebugMode) {
                    log.infof("loaded tenant %s (db=%s) partners=%d packages=%d",
                        tenant.Name, tenant.DbName, tenant.Context.Partners.size(),
                        tenant.Context.PartnerIdWisePackageAccounts.size());
                }

                TenantTreeBuilder.Finalize(tenant);
                roots.add(tenant);
            }

            _registry.Swap(roots);

            long durationMs = (System.nanoTime() - sw) / 1_000_000L;
            ConfigReloadedEvent evt = new ConfigReloadedEvent(trigger, roots.size(), durationMs, eventId);
            log.infof("config %s: %d tenant(s) loaded, %d ms%s",
                trigger, evt.TenantsLoaded(), evt.DurationMs(),
                eventId == null ? "" : " (eventId=" + eventId + ")");

            // Print the MediationContext each tenant received over HTTP (config-manager payload).
            for (Tenant root : roots) {
                for (Tenant t : root.Index.values()) {
                    MediationContext mc = t.Context.MediationContext;
                    log.infof(
                        "  mediationContext [%s]: %d categories, %d serviceGroupRules%s",
                        t.DbName, mc.Categories.size(), mc.ServiceGroupRules.size(),
                        mc.Categories.isEmpty()
                            ? ""
                            : " — " + mc.Categories.values().stream()
                                .map(c -> c.Id() + ":" + c.Type())
                                .collect(Collectors.joining(", ")));
                }
            }

            for (BiConsumer<Object, ConfigReloadedEvent> h : Reloaded) {
                h.accept(this, evt);
            }
            return evt;
        } finally {
            _gate.unlock();
        }
    }
}
