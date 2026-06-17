using Billing.Config.TenantConfigSync.Publishes;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// Refreshes config at the day boundary. config-manager scopes rates to "today" (LocalDate.now()), but
/// CDC config-events do NOT fire at midnight — so without this, the cache would keep yesterday's rates
/// into the new day. At each local midnight it triggers a full reload (which re-fetches today's rates),
/// then reschedules. On failure it keeps the last-good snapshot (the loader does not swap on error).
/// </summary>
internal sealed class DayBoundaryRefresher : IHostedService, IDisposable
{
    private readonly TenantHierarchyLoader _loader;
    private readonly ILogger<DayBoundaryRefresher> _log;
    private Timer? _timer;

    public DayBoundaryRefresher(TenantHierarchyLoader loader, ILogger<DayBoundaryRefresher> log)
    {
        _loader = loader;
        _log = log;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        ScheduleNextMidnight();
        return Task.CompletedTask;
    }

    private void ScheduleNextMidnight()
    {
        var now = DateTimeOffset.Now;
        var nextMidnight = now.Date.AddDays(1);                 // local midnight
        var delay = nextMidnight - now.LocalDateTime;
        if (delay < TimeSpan.FromSeconds(1)) delay = TimeSpan.FromSeconds(1);

        _timer?.Dispose();
        _timer = new Timer(_ => FireAndForget(), null, delay, Timeout.InfiniteTimeSpan);
        _log.LogInformation("day-boundary rate refresh scheduled for {Next} (in {Hours:F1}h)",
            nextMidnight, delay.TotalHours);
    }

    private void FireAndForget() => _ = RefreshAsync();

    private async Task RefreshAsync()
    {
        try
        {
            _log.LogInformation("day boundary reached — refreshing today's rates");
            await _loader.LoadAllAsync(ConfigReloadTrigger.DayRollover, eventId: null, CancellationToken.None);
        }
        catch (Exception ex)
        {
            _log.LogError(ex, "day-boundary refresh failed; keeping last-good rates");
        }
        finally
        {
            ScheduleNextMidnight();
        }
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _timer?.Dispose();
        return Task.CompletedTask;
    }

    public void Dispose() => _timer?.Dispose();
}
