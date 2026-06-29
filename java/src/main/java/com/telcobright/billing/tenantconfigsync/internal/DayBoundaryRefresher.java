package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.publishes.ConfigReloadTrigger;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Refreshes config at the day boundary. config-manager scopes rates to "today" (LocalDate.now()), but
 * CDC config-events do NOT fire at midnight — so without this, the cache would keep yesterday's rates
 * into the new day. At each local midnight it triggers a full reload (which re-fetches today's rates),
 * then reschedules. On failure it keeps the last-good snapshot (the loader does not swap on error).
 *
 * <p>Faithful-port note: C# implements {@code IHostedService, IDisposable}; the framework interfaces are
 * dropped (RULE 9 — no host/CDI glue) but the lifecycle methods are kept ({@link #Start}/{@link #Stop}/
 * {@link #Dispose}). {@code System.Threading.Timer} → a single-thread {@link ScheduledExecutorService};
 * {@code DateTimeOffset.Now} → {@link LocalDateTime#now()}; {@code ILogger} injection → static logger.
 */
public final class DayBoundaryRefresher {
    private static final Logger log = Logger.getLogger(DayBoundaryRefresher.class);

    private final TenantHierarchyLoader _loader;
    private final ScheduledExecutorService _executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> _timer;

    public DayBoundaryRefresher(TenantHierarchyLoader loader) {
        _loader = loader;
    }

    public void Start() {
        ScheduleNextMidnight();
    }

    private void ScheduleNextMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();   // local midnight
        Duration delay = Duration.between(now, nextMidnight);
        if (delay.compareTo(Duration.ofSeconds(1)) < 0) {
            delay = Duration.ofSeconds(1);
        }

        if (_timer != null) {
            _timer.cancel(false);
        }
        _timer = _executor.schedule(this::FireAndForget, delay.toMillis(), TimeUnit.MILLISECONDS);
        log.infof("day-boundary rate refresh scheduled for %s (in %.1fh)",
            nextMidnight, delay.toMillis() / 3_600_000.0);
    }

    private void FireAndForget() {
        Refresh();
    }

    private void Refresh() {
        try {
            log.info("day boundary reached — refreshing today's rates");
            _loader.LoadAll(ConfigReloadTrigger.DayRollover, null);
        } catch (RuntimeException ex) {
            log.error("day-boundary refresh failed; keeping last-good rates", ex);
        } finally {
            ScheduleNextMidnight();
        }
    }

    public void Stop() {
        if (_timer != null) {
            _timer.cancel(false);
        }
        _executor.shutdownNow();
    }

    public void Dispose() {
        if (_timer != null) {
            _timer.cancel(false);
        }
        _executor.shutdownNow();
    }
}
