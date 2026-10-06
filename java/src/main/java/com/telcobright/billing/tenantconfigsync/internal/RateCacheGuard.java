package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.rating.ratecaching.RateCache;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>A day that fails to load is retried, not hammered. TODAY is attempted every period (the realtime day; it is
 * normally cached, so this costs nothing). TOMORROW backs off after a failure — 1, 2, 4, 8, 16, then every 30
 * minutes — and is tried again at once when the day rolls over and it becomes today. Nothing is hidden: every
 * failed attempt logs a WARN (with the stack trace on the first of a run), a recovery logs once, and the rating
 * path still does its own fetch, so a CDR for a day that cannot be loaded fails with the real cause. Before this,
 * a config-manager answering {@code /get-rates-by-date} with HTTP 500 drew one request and one stack trace per
 * tenant every minute, forever (2026-10-07).</p>
 *
 * <p>Complements {@link DayBoundaryRefresher} (which re-fetches the whole tree's rate DATA at midnight): this
 * only ensures the two realtime days are present in each already-loaded cache.
 */
public final class RateCacheGuard {
    private static final Logger log = Logger.getLogger(RateCacheGuard.class);
    private static final long PeriodSeconds = 60;
    /** The longest wait between attempts for a tomorrow that keeps failing. */
    static final Duration MaxTomorrowBackoff = Duration.ofMinutes(30);
    // Fixed-rate ticks drift a little; an attempt that falls due within this grace runs on the current tick.
    private static final Duration TickGrace = Duration.ofSeconds(5);

    private record DayKey(String tenant, LocalDate day) {}

    /** A run of failed attempts for one tenant's day, and the earliest time of the next attempt. */
    private record Failures(int count, Instant nextAttempt) {}

    private final ITenantRegistry _registry;
    private final Clock _clock;
    // Only today/tomorrow keys are ever added and days before today are pruned every tick: two per tenant at most.
    private final Map<DayKey, Failures> _failures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService _executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ratecache-guard");
        t.setDaemon(true);
        return t;
    });

    public RateCacheGuard(ITenantRegistry registry) {
        this(registry, Clock.systemDefaultZone());
    }

    /** Test seam: a hand-driven clock, so a test can run many guard periods instantly. */
    RateCacheGuard(ITenantRegistry registry, Clock clock) {
        _registry = registry;
        _clock = clock;
    }

    public void Start() {
        _executor.scheduleAtFixedRate(this::Tick, PeriodSeconds, PeriodSeconds, TimeUnit.SECONDS);
        log.infof("ratecache guard armed (every %ds: ensure today+tomorrow per tenant)", PeriodSeconds);
    }

    void Tick() {
        try {
            Instant now = _clock.instant();
            LocalDate today = LocalDate.ofInstant(now, _clock.getZone());
            _failures.keySet().removeIf(k -> k.day().isBefore(today));
            for (Tenant root : _registry.Roots()) Warm(root, today, now);
        } catch (RuntimeException ex) {
            log.warn("ratecache guard tick failed; will retry next period", ex);
        }
    }

    private void Warm(Tenant tenant, LocalDate today, Instant now) {
        if (tenant == null) return;
        if (tenant.Context != null
                && tenant.Context.MediationContext != null
                && tenant.Context.MediationContext.RateCache != null) {
            RateCache cache = tenant.Context.MediationContext.RateCache;
            WarmDay(tenant, cache, today, false, now);
            WarmDay(tenant, cache, today.plusDays(1), true, now);
        }
        if (tenant.Children != null)
            for (Tenant child : tenant.Children.values()) Warm(child, today, now);
    }

    private void WarmDay(Tenant tenant, RateCache cache, LocalDate day, boolean tomorrow, Instant now) {
        if (cache.IsDayLoaded(day)) return;
        DayKey key = new DayKey(tenant.DbName, day);
        Failures prior = _failures.get(key);
        if (tomorrow && prior != null && now.plus(TickGrace).isBefore(prior.nextAttempt())) return;   // backing off
        try {
            cache.EnsureDay(day);
            if (prior != null) {
                _failures.remove(key);
                log.infof("ratecache guard: %s rates for tenant '%s' loaded after %d failed attempt(s)",
                        day, tenant.DbName, prior.count());
            }
        } catch (RuntimeException ex) {
            int count = prior == null ? 1 : prior.count() + 1;
            Duration wait = tomorrow ? Backoff(count) : Duration.ofSeconds(PeriodSeconds);
            _failures.put(key, new Failures(count, now.plus(wait)));
            String which = (tomorrow ? "tomorrow's" : "today's") + " (" + day + ")";
            if (count == 1)
                log.warnf(ex, "ratecache guard: could not warm %s rates for tenant '%s'; next attempt in %ds",
                        which, tenant.DbName, wait.toSeconds());
            else
                log.warnf("ratecache guard: still cannot warm %s rates for tenant '%s' (%d attempts in a row): %s; "
                        + "next attempt in %ds", which, tenant.DbName, count, ex.getMessage(), wait.toSeconds());
        }
    }

    /** The wait after the n-th failure in a row: 1, 2, 4, 8, 16 periods, then {@link #MaxTomorrowBackoff}. */
    static Duration Backoff(int failures) {
        long seconds = PeriodSeconds << Math.min(failures - 1, 10);
        return Duration.ofSeconds(Math.min(seconds, MaxTomorrowBackoff.toSeconds()));
    }

    /** How many tenant-days are failing right now (stays bounded: today/tomorrow per tenant). */
    int FailingDays() {
        return _failures.size();
    }

    public void Stop() {
        _executor.shutdownNow();
    }
}
