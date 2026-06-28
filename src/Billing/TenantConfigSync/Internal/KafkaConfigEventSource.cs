using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.Options;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// The host-side Kafka adapter implementing <see cref="IConfigEventSource"/> — config-sync infrastructure,
/// NOT a bean: it listens to routesphere's <c>config_event_loader_&lt;tenant&gt;</c> topics and raises a
/// <see cref="ConfigChangeSignal"/> per "tenant X changed" message (never the config itself). The CDR
/// processor (the bean) uses config-sync underneath; this adapter is what keeps it fed. A THIN adapter —
/// construction + start/stop the listen-loop; the consume machinery is in <see cref="ConfigEventConsumerLoop"/>.
/// </summary>
public sealed class KafkaConfigEventSource : IConfigEventSource
{
    private readonly TenantSelection _selection;
    private readonly ConfigEventsOptions _opts;
    private readonly ILogger<KafkaConfigEventSource> _log;

    private ConfigEventConsumerLoop? _loop;

    public KafkaConfigEventSource(TenantSelection selection,
        IOptions<TenantConfigSyncOptions> opts, ILogger<KafkaConfigEventSource> log)
    {
        _selection = selection;
        _opts = opts.Value.ConfigEvents;
        _log = log;
    }

    /// <summary>Start listening: subscribe to the enabled tenants' topics and poll in the background.</summary>
    public Task StartAsync(Func<ConfigChangeSignal, Task> onSignal, CancellationToken ct)
    {
        _loop = ConfigEventConsumerLoop.Start(_selection, _opts, _log, onSignal, ct);
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken ct) => _loop?.StopAsync(ct) ?? Task.CompletedTask;
}
