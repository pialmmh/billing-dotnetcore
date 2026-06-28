namespace Billing.Config.TenantConfigSync.Spi;

/// <summary>One config-change signal — "tenant X changed, re-fetch". Carries no config data.</summary>
public sealed record ConfigChangeSignal(string Tenant, int ChangeCount, string? EventId);

/// <summary>
/// What this package REQUIRES to know config changed: a source of change signals. routesphere drives
/// this off Kafka (topic <c>config_event_loader_&lt;tenant&gt;</c>); the host provides that adapter and
/// injects it. The package stays free of any broker dependency — it only consumes the SPI. Absence of
/// a source is information: with none registered, config loads once on start and never reloads.
/// </summary>
public interface IConfigEventSource
{
    /// <summary>Begin delivering signals to <paramref name="onSignal"/>. Returns when subscribed.</summary>
    Task StartAsync(Func<ConfigChangeSignal, Task> onSignal, CancellationToken ct);

    /// <summary>Stop delivering signals.</summary>
    Task StopAsync(CancellationToken ct);
}
