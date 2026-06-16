using Billing.Config.TenantConfigSync.Publishes;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// The lifecycle: on start, populate every tenant's DynamicContext (+ MediationContext) from
/// config-manager, then begin listening for change signals; each signal feeds the debounced reloader.
/// With no <see cref="IConfigEventSource"/> registered, config simply loads once and never reloads —
/// absence of a source is a valid configuration, not an error.
/// </summary>
internal sealed class TenantConfigHostedService : IHostedService
{
    private readonly TenantHierarchyLoader _loader;
    private readonly DebouncedReloader _reloader;
    private readonly IEnumerable<IConfigEventSource> _sources;
    private readonly ILogger<TenantConfigHostedService> _log;

    public TenantConfigHostedService(TenantHierarchyLoader loader, DebouncedReloader reloader,
        IEnumerable<IConfigEventSource> sources, ILogger<TenantConfigHostedService> log)
    {
        _loader = loader;
        _reloader = reloader;
        _sources = sources;
        _log = log;
    }

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        await _loader.LoadAllAsync(ConfigReloadTrigger.Startup, eventId: null, cancellationToken);

        var sourceCount = 0;
        foreach (var source in _sources)
        {
            await source.StartAsync(signal => { _reloader.Signal(signal); return Task.CompletedTask; },
                cancellationToken);
            sourceCount++;
        }

        _log.LogInformation(sourceCount == 0
            ? "tenant config loaded; no config-event source — reloads disabled"
            : "tenant config loaded; listening on {Count} config-event source(s)", sourceCount);
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        foreach (var source in _sources)
            await source.StopAsync(cancellationToken);
        _reloader.Dispose();
    }
}
