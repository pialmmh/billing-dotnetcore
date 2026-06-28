using System.Text.Json;
using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Spi;
using Confluent.Kafka;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>
/// The Kafka consume machinery behind <see cref="KafkaConfigEventSource"/>: builds the consumer, subscribes
/// to each enabled tenant's <c>config_event_loader_&lt;tenant&gt;</c> topic, and polls in a background task —
/// turning each "tenant X changed" message into a <see cref="ConfigChangeSignal"/>. A consume error (e.g. the
/// topic doesn't exist yet) is logged ONCE, then retried with a backoff. This is the encapsulated detail; the
/// bean surface only starts/stops it.
/// </summary>
internal sealed class ConfigEventConsumerLoop
{
    private const int ErrorBackoffSeconds = 5;

    private readonly IConsumer<Ignore, string> _consumer;
    private readonly ConfigEventsOptions _opts;
    private readonly ILogger _log;
    private readonly CancellationTokenSource _cts;
    private Task _task = Task.CompletedTask;

    private ConfigEventConsumerLoop(IConsumer<Ignore, string> consumer, ConfigEventsOptions opts,
        ILogger log, CancellationToken ct)
    {
        _consumer = consumer;
        _opts = opts;
        _log = log;
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
    }

    /// <summary>Build the consumer, subscribe, and launch the poll loop. Returns <c>null</c> when no tenants
    /// are enabled (nothing to listen to) — the caller then stays idle.</summary>
    public static ConfigEventConsumerLoop? Start(TenantSelection selection, ConfigEventsOptions opts,
        ILogger log, Func<ConfigChangeSignal, Task> onSignal, CancellationToken ct)
    {
        var topics = selection.Enabled
            .Select(t => $"{opts.EventTopicBase}_{t.Name}")
            .Distinct()
            .ToList();
        if (topics.Count == 0)
        {
            log.LogInformation("no enabled tenants — Kafka config-event source idle");
            return null;
        }

        var consumer = new ConsumerBuilder<Ignore, string>(new ConsumerConfig
        {
            BootstrapServers = opts.BootstrapServers,
            GroupId = opts.ConsumerGroupBase,
            AutoOffsetReset = AutoOffsetReset.Latest,
            EnableAutoCommit = true,
        }).Build();
        consumer.Subscribe(topics);

        var loop = new ConfigEventConsumerLoop(consumer, opts, log, ct);
        loop._task = Task.Run(() => loop.RunAsync(onSignal, loop._cts.Token));

        log.LogInformation("Kafka config-event source listening on {Topics} (servers={Servers})",
            string.Join(",", topics), opts.BootstrapServers);
        return loop;
    }

    private async Task RunAsync(Func<ConfigChangeSignal, Task> onSignal, CancellationToken ct)
    {
        var warned = false;   // log a recurring error ONCE, not on every poll
        try
        {
            while (!ct.IsCancellationRequested)
            {
                ConsumeResult<Ignore, string>? result;
                try
                {
                    result = _consumer.Consume(ct);
                    warned = false;   // healthy again
                }
                catch (ConsumeException ex)
                {
                    // e.g. the topic does not exist yet — expected until config-manager creates/publishes
                    // to it. Log once, then back off and retry quietly instead of spamming every poll.
                    if (!warned)
                    {
                        _log.LogWarning("Kafka consume error ({Reason}); retrying every {Backoff}s until it clears",
                            ex.Error.Reason, ErrorBackoffSeconds);
                        warned = true;
                    }
                    await Task.Delay(TimeSpan.FromSeconds(ErrorBackoffSeconds), ct);
                    continue;
                }
                if (result?.Message?.Value is not { } value) continue;

                await onSignal(ParseSignal(TenantFromTopic(result.Topic), value));
            }
        }
        catch (OperationCanceledException) { /* normal shutdown */ }
    }

    private string TenantFromTopic(string topic)
    {
        var prefix = _opts.EventTopicBase + "_";
        return topic.StartsWith(prefix, StringComparison.Ordinal) ? topic[prefix.Length..] : topic;
    }

    private static ConfigChangeSignal ParseSignal(string tenant, string json)
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
        await _cts.CancelAsync();
        try { await _task; } catch (OperationCanceledException) { }
        _consumer.Close();
        _consumer.Dispose();
    }
}
