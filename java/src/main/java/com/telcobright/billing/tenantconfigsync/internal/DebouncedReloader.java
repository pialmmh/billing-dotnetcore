package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.dependencies.ConfigEventsOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.publishes.ConfigReloadTrigger;
import com.telcobright.billing.tenantconfigsync.spi.ConfigChangeSignal;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Collapses a burst of config-change signals into one reload. Trailing-edge: each signal restarts a
 * timer, and the reload fires only after {@code DebounceMs} of quiet. Leading-edge fast path: if we've
 * been idle longer than {@code DebounceMs × IdleFastPathMultiplier}, reload immediately. Mirrors
 * routesphere's ConfigEventConsumer.scheduleReload. One timer, guarded by a lock.
 *
 * <p>Faithful-port note: {@code System.Threading.Timer} → a single-thread
 * {@link ScheduledExecutorService} ({@code _timer} is the pending one-shot task, re-armed per signal).
 * {@code Environment.TickCount64} → monotonic {@code System.nanoTime()/1e6}. {@code ILogger}/{@code IOptions}
 * injection dropped. C# {@code internal} → Java {@code public} for cross-package wiring.
 */
public final class DebouncedReloader {
    private static final Logger log = Logger.getLogger(DebouncedReloader.class);

    private final TenantHierarchyLoader _loader;
    private final ConfigEventsOptions _opts;

    private final Object _lock = new Object();
    private final ScheduledExecutorService _executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> _timer;
    private int _pendingSignals;
    private String _lastEventId;
    private long _lastReloadTicks;

    public DebouncedReloader(TenantHierarchyLoader loader, TenantConfigSyncOptions opts) {
        _loader = loader;
        _opts = opts.ConfigEvents;
    }

    /** Record a change signal and (re)arm the debounce timer. */
    public void Signal(ConfigChangeSignal signal) {
        synchronized (_lock) {
            _pendingSignals += signal.ChangeCount() <= 0 ? 1 : signal.ChangeCount();
            _lastEventId = signal.EventId() != null ? signal.EventId() : _lastEventId;

            long idleThreshold = (long) _opts.DebounceMs * Math.max(1, _opts.IdleFastPathMultiplier);
            boolean idle = (nowMillis() - _lastReloadTicks) > idleThreshold;
            long delay = idle ? 0 : _opts.DebounceMs;

            if (_timer != null) {
                _timer.cancel(false);
            }
            _timer = _executor.schedule(this::FireAndForget, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void FireAndForget() {
        int batched;
        String eventId;
        synchronized (_lock) {
            batched = _pendingSignals;
            _pendingSignals = 0;
            eventId = _lastEventId;
            _lastEventId = null;
            _lastReloadTicks = nowMillis();
        }

        log.infof("debounce complete — %d signal(s) batched, reloading", batched);
        Reload(eventId);
    }

    private void Reload(String eventId) {
        try {
            _loader.LoadAll(ConfigReloadTrigger.ConfigEvent, eventId);
        } catch (RuntimeException ex) {
            log.errorf(ex, "config reload failed (eventId=%s)", eventId);
        }
    }

    public void Dispose() {
        synchronized (_lock) {
            if (_timer != null) {
                _timer.cancel(false);
            }
        }
        _executor.shutdownNow();
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
