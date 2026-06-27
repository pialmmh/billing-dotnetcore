namespace Billing.Config.TenantConfigSync.Dependencies;

/// <summary>
/// The decoupled summary hand-off switch — read 100% from the active profile's <c>billing.summary</c> block
/// (no environment variables). When <see cref="Enabled"/> is false (default) billing rolls up + writes the
/// summaries itself inline (legacy). When true, the cdr batch writes ONE compressed <c>summary_affected</c>
/// outbox row (atomic with the cdr write) and fires a best-effort Kafka ping; the summary-service consumes it.
///
/// <para><see cref="BootstrapServers"/> may be empty — then the outbox row is still written and the
/// summary-service picks it up by polling; the ping only reduces latency.</para>
/// </summary>
public sealed class SummaryOutboxOptions
{
    public bool Enabled { get; set; }
    public string EntityType { get; set; } = "cdr";
    public string PingTopic { get; set; } = "cdr_summary_ping";
    public string? BootstrapServers { get; set; }
}
