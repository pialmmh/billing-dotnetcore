package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every-minute guard that keeps each tenant's RateCache holding TODAY + TOMORROW. Between config reloads the
 * cache can lose those days — a back-processing flush-all evicts everything, a day rollover shifts which day is
 * "today", or a reload swaps in a fresh (lazy) cache. Without this, the first realtime call after such an event
 * could pay a synchronous rebuild/fetch. The guard reloads them ahead of time (in-memory from the pushed
 * snapshot when possible), so the rating path stays wait-free. Per-tenant failures are logged, never fatal.
 *
 * <p>Complements {@link DayBoundaryRefresher} (which re-fetches the whole tree's rate DATA at midnight): this
 * only ensures the two realtime days are present in each already-loaded cache.
 */
public final class RateCacheGuard {
    private static final Logger log = Logger.getLogger(RateCacheGuard.class);
    private static final long PeriodSeconds = 60;

    private final ITenantRegistry _registry;
    private final ScheduledExecutorService _executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ratecache-guard");
        t.setDaemon(true);
        return t;
    });

    public RateCacheGuard(ITenantRegistry registry) {
        _registry = registry;
    }

    public void Start() {
        _executor.scheduleAtFixedRate(this::Tick, PeriodSeconds, PeriodSeconds, TimeUnit.SECONDS);
        log.infof("ratecache guard armed (every %ds: ensure today+tomorrow per tenant)", PeriodSeconds);
    }

    private void Tick() {
        try {
            for (Tenant root : _registry.Roots()) Warm(root);
        } catch (RuntimeException ex) {
            log.warn("ratecache guard tick failed; will retry next period", ex);
        }
    }

    private void Warm(Tenant tenant) {
        if (tenant == null) return;
        try {
            if (tenant.Context != null
                    && tenant.Context.MediationContext != null
                    && tenant.Context.MediationContext.RateCache != null) {
                tenant.Context.MediationContext.RateCache.EnsureTodayTomorrow();
            }
        } catch (RuntimeException ex) {
            log.warnf(ex, "ratecache guard: could not warm today+tomorrow for tenant '%s'", tenant.DbName);
        }
        if (tenant.Children != null)
            for (Tenant child : tenant.Children.values()) Warm(child);
    }

    public void Stop() {
        _executor.shutdownNow();
    }
}
