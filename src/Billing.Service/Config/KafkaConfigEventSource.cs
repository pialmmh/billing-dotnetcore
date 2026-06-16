using System.Text.Json;
using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Spi;
using Confluent.Kafka;
using Microsoft.Extensions.Options;

namespace Billing.Service.Config;

/// <summary>
/// The host-provided <see cref="IConfigEventSource"/>: a Kafka consumer over routesphere's
/// <c>config_event_loader_&lt;tenant&gt;</c> topics. It never carries config data — each message is just
/// "tenant X changed, re-fetch", turned into a <see cref="ConfigChangeSignal"/>. The package stays free
/// of any Kafka dependency; only this adapter (in the host) references Confluent.Kafka.
/// </summary>
public sealed class KafkaConfigEventSource : IConfigEventSource
{
    private readonly ConfigEventsOptions _opts;
    private readonly TenantSelection _selection;
    private readonly ILogger<KafkaConfigEventSource> _log;

    private IConsumer<Ignore, string>? _consumer;
    private CancellationTokenSource? _cts;
    private Task? _loop;

    public KafkaConfigEventSource(TenantSelection selection,
        IOptions<TenantConfigSyncOptions> opts, ILogger<KafkaConfigEventSource> log)
    {
        _selection = selection;
        _opts = opts.Value.ConfigEvents;
        _log = log;
    }

    public Task StartAsync(Func<ConfigChangeSignal, Task> onSignal, CancellationToken ct)
    {
        var topics = _selection.Enabled
            .Select(t => $"{_opts.EventTopicBase}_{t.Name}")
            .Distinct()
            .ToList();
        if (topics.Count == 0)
        {
            _log.LogInformation("no enabled tenants — Kafka config-event source idle");
            return Task.CompletedTask;
        }

        var config = new ConsumerConfig
        {
            BootstrapServers = _opts.BootstrapServers,
            GroupId = _opts.ConsumerGroupBase,
            AutoOffsetReset = AutoOffsetReset.Latest,
            EnableAutoCommit = true,
        };
        _consumer = new ConsumerBuilder<Ignore, string>(config).Build();
        _consumer.Subscribe(topics);

        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _loop = Task.Run(() => ConsumeLoop(onSignal, _cts.Token));

        _log.LogInformation("Kafka config-event source listening on {Topics} (servers={Servers})",
            string.Join(",", topics), _opts.BootstrapServers);
        return Task.CompletedTask;
    }

    private async Task ConsumeLoop(Func<ConfigChangeSignal, Task> onSignal, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                ConsumeResult<Ignore, string>? result;
                try
                {
                    result = _consumer!.Consume(ct);
                }
                catch (ConsumeException ex)
                {
                    _log.LogWarning(ex, "Kafka consume error; continuing");
                    continue;
                }
                if (result?.Message?.Value is not { } value) continue;

                var tenant = TenantFromTopic(result.Topic);
                var signal = ParseSignal(tenant, value);
                await onSignal(signal);
            }
        }
        catch (OperationCanceledException) { /* normal shutdown */ }
    }

    private string TenantFromTopic(string topic)
    {
        var prefix = _opts.EventTopicBase + "_";
        return topic.StartsWith(prefix, StringComparison.Ordinal) ? topic[prefix.Length..] : topic;
    }

    private ConfigChangeSignal ParseSignal(string tenant, string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var count = root.TryGetProperty("changeCount", out var c) && c.TryGetInt32(out var n) ? n : 1;
            var eventId = root.TryGetProperty("eventId", out var e) ? e.GetString() : null;
            return new ConfigChangeSignal(tenant, count, eventId);
        }
        catch (JsonException)
        {
            return new ConfigChangeSignal(tenant, 1, null);
        }
    }

    public async Task StopAsync(CancellationToken ct)
    {
        if (_cts is not null) await _cts.CancelAsync();
        if (_loop is not null)
        {
            try { await _loop; } catch (OperationCanceledException) { }
        }
        _consumer?.Close();
        _consumer?.Dispose();
    }
}
