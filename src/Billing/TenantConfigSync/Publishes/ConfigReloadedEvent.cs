namespace Billing.Config.TenantConfigSync.Publishes;

/// <summary>
/// Raised after a load/reload swaps the registry snapshot. One concrete type per kind.
/// Consumers (metrics, warm-up, the rater's cache invalidation) subscribe to react; nobody
/// needs to poll. <paramref name="Trigger"/> distinguishes the start-up load from a Kafka reload.
/// A load is all-or-nothing (fail-fast), so a raised event always means every tenant loaded.
/// </summary>
public sealed record ConfigReloadedEvent(
    ConfigReloadTrigger Trigger,
    int TenantsLoaded,
    long DurationMs,
    string? EventId);

public enum ConfigReloadTrigger
{
    Startup = 0,
    ConfigEvent = 1,
    DayRollover = 2
}
