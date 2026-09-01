package com.telcobright.billing.tenantconfigsync.dependencies;

/**
 * Profile-bound config for the summary ROLL-UP consumer (the {@code billing.summary-rollup} block) — the
 * decoupled side that folds each tenant's {@code summary_affected} outbox into its {@code sum_voice_day/hr}
 * tables. Distinct from {@link SummaryOutboxOptions}, which is the PRODUCER side (the pipeline's outbox write +
 * the best-effort Kafka ping). When {@link #Enabled} is false the consumer loop is not started (the outbox rows
 * just accumulate until something drains them). 100% config — no environment-variable override.
 */
public final class SummaryRollupOptions {
    public boolean Enabled;
    /** The {@code summary_affected.entity_type} this consumer drains + its {@code summary_offset} cursor key. */
    public String EntityType = "cdr";
    /** How often to sweep every tenant schema for new outbox rows. */
    public int PollMs = 2000;
    /** Max outbox rows folded per transaction per tenant (the consumer drains a tenant page-by-page). */
    public int MaxRowsPerPoll = 500;
    /** Row-count per segmented INSERT/UPDATE to the sum_voice tables (max_allowed_packet safety). */
    public int SegmentSize = 1000;
}
