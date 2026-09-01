package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * The decoupled summary hand-off switch — read 100% from the active profile's {@code billing.summary} block
 * (no environment variables). Summaries are OUTBOX-only: the cdr batch ALWAYS writes ONE compressed
 * {@code summary_affected} row (atomic with the cdr write) and the summary-service consumes it. {@link #Enabled}
 * only gates the best-effort Kafka ping that nudges the summary-service (it also polls), so the durable
 * hand-off — the outbox row — is written regardless of this flag.
 *
 * <p>{@link #BootstrapServers} may be empty — then no ping is sent and the summary-service picks the outbox
 * row up by polling; the ping only reduces latency.
 */
public final class SummaryOutboxOptions {
    public boolean Enabled;
    public String EntityType = "cdr";
    public String PingTopic = "cdr_summary_ping";
    public String BootstrapServers;
}
