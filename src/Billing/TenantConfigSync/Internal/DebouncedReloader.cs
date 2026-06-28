using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Publishes;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// Collapses a burst of config-change signals into one reload. Trailing-edge: each signal restarts a
/// timer, and the reload fires only after <c>DebounceMs</c> of quiet. Leading-edge fast path: if we've
/// been idle longer than <c>DebounceMs × IdleFastPathMultiplier</c>, reload immediately. Mirrors
/// routesphere's ConfigEventConsumer.scheduleReload. One timer, guarded by a lock.
/// </summary>
internal sealed class DebouncedReloader : IDisposable
{
    private readonly TenantHierarchyLoader _loader;
    private readonly ConfigEventsOptions _opts;
    private readonly ILogger<DebouncedReloader> _log;

    private readonly object _lock = new();
    private Timer? _timer;
    private int _pendingSignals;
    private string? _lastEventId;
    private long _lastReloadTicks;

    public DebouncedReloader(TenantHierarchyLoader loader,
        IOptions<TenantConfigSyncOptions> opts, ILogger<DebouncedReloader> log)
    {
        _loader = loader;
        _opts = opts.Value.ConfigEvents;
        _log = log;
    }

    /// <summary>Record a change signal and (re)arm the debounce timer.</summary>
    public void Signal(ConfigChangeSignal signal)
    {
        lock (_lock)
        {
            _pendingSignals += signal.ChangeCount <= 0 ? 1 : signal.ChangeCount;
            _lastEventId = signal.EventId ?? _lastEventId;

            var idleThreshold = (long)_opts.DebounceMs * Math.Max(1, _opts.IdleFastPathMultiplier);
            var idle = (Environment.TickCount64 - _lastReloadTicks) > idleThreshold;
            var delay = idle ? 0 : _opts.DebounceMs;

            _timer?.Dispose();
            _timer = new Timer(_ => FireAndForget(), null, delay, Timeout.Infinite);
        }
    }

    private void FireAndForget()
    {
        int batched;
        string? eventId;
        lock (_lock)
        {
            batched = _pendingSignals;
            _pendingSignals = 0;
            eventId = _lastEventId;
            _lastEventId = null;
            _lastReloadTicks = Environment.TickCount64;
        }

        _log.LogInformation("debounce complete — {Batched} signal(s) batched, reloading", batched);
        _ = ReloadAsync(eventId);
    }

    private async Task ReloadAsync(string? eventId)
    {
        try
        {
            await _loader.LoadAllAsync(ConfigReloadTrigger.ConfigEvent, eventId, CancellationToken.None);
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "config reload failed (eventId={EventId})", eventId);
        }
    }

    public void Dispose()
    {
        lock (_lock) _timer?.Dispose();
    }
}
