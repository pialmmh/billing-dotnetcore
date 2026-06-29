package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * The decoupled summary hand-off switch — read 100% from the active profile's {@code billing.summary} block
 * (no environment variables). When {@link #Enabled} is false (default) billing rolls up + writes the
 * summaries itself inline (legacy). When true, the cdr batch writes ONE compressed {@code summary_affected}
 * outbox row (atomic with the cdr write) and fires a best-effort Kafka ping; the summary-service consumes it.
 *
 * <p>{@link #BootstrapServers} may be empty — then the outbox row is still written and the
 * summary-service picks it up by polling; the ping only reduces latency.
 */
public final class SummaryOutboxOptions {
    public boolean Enabled;
    public String EntityType = "cdr";
    public String PingTopic = "cdr_summary_ping";
    public String BootstrapServers;
}
