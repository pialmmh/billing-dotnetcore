using Confluent.Kafka;
using Microsoft.Extensions.Options;

namespace Billing.Service.Adapters;

/// <summary>
/// Best-effort Kafka PING for the decoupled summary path. After a cdr batch commits in OUTBOX mode, this
/// notifies "tenant X has a new batch for entity Y" so the summary-service processes it promptly instead of
/// waiting for its next poll. The message carries NO cdr data — the durable hand-off is the
/// <c>summary_affected</c> row. The producer is the only Kafka <i>producer</i> in the host (mirrors
/// <see cref="KafkaConfigEventSource"/>'s consumer setup).
///
/// <para><see cref="Ping"/> NEVER throws and NEVER blocks the request: the outbox row is already committed and
/// the summary-service also polls, so a missed/failed ping costs only latency. If outbox mode is off or no
/// broker is configured, this is a no-op.</para>
/// </summary>
public sealed class SummaryPingPublisher : IDisposable
{
    private readonly IProducer<Null, string>? _producer;
    private readonly string _topic;
    private readonly ILogger<SummaryPingPublisher> _log;

    public SummaryPingPublisher(IOptions<SummaryOutboxOptions> opts, ILogger<SummaryPingPublisher> log)
    {
        _log = log;
        var o = opts.Value;
        _topic = o.PingTopic;
        if (o.Enabled && !string.IsNullOrWhiteSpace(o.BootstrapServers))
        {
            try
            {
                _producer = new ProducerBuilder<Null, string>(
                    new ProducerConfig { BootstrapServers = o.BootstrapServers }).Build();
                _log.LogInformation("summary ping publisher → topic {Topic} (servers={Servers})", _topic, o.BootstrapServers);
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "summary ping publisher init failed; pings disabled (summary-service will poll)");
            }
        }
    }

    /// <summary>Fire-and-forget notification of a freshly committed outbox batch. Non-fatal on any failure.</summary>
    public void Ping(string tenant, string entityType, int rows)
    {
        if (_producer is null) return;
        try
        {
            _producer.Produce(_topic, new Message<Null, string>
            {
                Value = $"{{\"tenant\":\"{tenant}\",\"entity\":\"{entityType}\",\"rows\":{rows}}}",
            });
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "summary ping failed for tenant {Tenant} (non-fatal; summary-service polls)", tenant);
        }
    }

    public void Dispose()
    {
        try { _producer?.Flush(TimeSpan.FromSeconds(2)); } catch { /* best effort on shutdown */ }
        _producer?.Dispose();
    }
}
