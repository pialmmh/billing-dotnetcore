package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * Profile-bound config for the inbound Kafka CDR ingest loop (the {@code billing.cdr-ingest} block). Mirrors
 * {@link ConfigEventsOptions}: where the broker is, which topic carries the rated CDRs, the consumer group, and
 * the dead-letter topic. When {@link #Enabled} is false the loop is not started (cdrs then arrive only via the
 * gRPC {@code ProcessCdrBatch} debug entry).
 */
public final class CdrIngestOptions {
    public boolean Enabled;
    public String BootstrapServers = "";
    /** The rated-CDR topic (key = channelCallUuid, value = CdrEvent[] for one call). */
    public String Topic = "cdr";
    public String ConsumerGroup = "billing-core-cdr-ingest";
    /** Records that fail decode/validation are logged and (if set) republished here. */
    public String DeadLetterTopic = "cdr_dlq";
    public int PollMs = 500;
}
