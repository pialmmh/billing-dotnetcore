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
    /** Where the records this service refuses go ({@code cdr_dlq_<root tenant>} on the ratified wire). REQUIRED,
     * and the topic must EXIST: the ingest consumes nothing until it does — a refused record must never be lost
     * behind a committed offset. */
    public String DeadLetterTopic = "cdr_dlq";
    /** After this many failed tries to publish one batch's dead letters the health road goes red
     * ({@code billing.cdr-ingest.dead-letter-unhealthy-after-tries}). The batch is held and retried regardless. */
    public int DeadLetterUnhealthyAfterTries = 3;
    public int PollMs = 500;
    /**
     * Where a consumer group with NO committed offset starts ({@code billing.cdr-ingest.auto-offset-reset}):
     * {@code earliest} (the ratified default — nothing published before the group's first start may be skipped)
     * or {@code latest}. It has no effect on a group that already has offsets.
     */
    public String AutoOffsetReset = "earliest";

    /**
     * CUTOVER feature flag ({@code billing.cdr-ingest.legacy-dedup-enabled}, default {@code false}). When ON,
     * each batch drops any cdr whose {@code SequenceNumber} is already owned by legacy (present in the tenant's
     * {@code cdr} OR {@code cdrerror}) BEFORE billing — the legacy→new ownership boundary. OFF = the normal path
     * is completely unchanged. Only turn this on during a controlled cutover.
     */
    public boolean LegacyDedupEnabled = false;
}
