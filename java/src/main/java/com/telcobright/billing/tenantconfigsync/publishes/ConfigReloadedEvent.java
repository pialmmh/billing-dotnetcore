package com.telcobright.billing.tenantconfigsync.publishes;

/**
 * Raised after a load/reload swaps the registry snapshot. One concrete type per kind.
 * Consumers (metrics, warm-up, the rater's cache invalidation) subscribe to react; nobody
 * needs to poll. {@code Trigger} distinguishes the start-up load from a Kafka reload.
 * A load is all-or-nothing (fail-fast), so a raised event always means every tenant loaded.
 */
public record ConfigReloadedEvent(
    ConfigReloadTrigger Trigger,
    int TenantsLoaded,
    long DurationMs,
    String EventId) {
}
