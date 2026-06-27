namespace Billing.Service;

/// <summary>
/// Operational switch for the decoupled summary hand-off (config section <c>Billing:Summary</c>). When
/// <see cref="Enabled"/> is false (default) billing rolls up + writes the summaries itself, inline, exactly as
/// before. When true, the cdr batch writes ONE compressed <c>summary_affected</c> outbox row instead (atomic
/// with the cdr write) and fires a best-effort Kafka PING — the summary-service consumes the row and rolls up.
///
/// <para>The outbox tables must exist in each tenant schema first (<c>src/Billing.Data/Sql/summary_outbox.sql</c>).
/// <see cref="BootstrapServers"/> is optional: with no broker the outbox row is still written and the
/// summary-service picks it up by polling — the ping only reduces latency.</para>
/// </summary>
public sealed class SummaryOutboxOptions
{
    public bool Enabled { get; set; }
    public string EntityType { get; set; } = "cdr";
    public string PingTopic { get; set; } = "cdr_summary_ping";
    public string? BootstrapServers { get; set; }
}
