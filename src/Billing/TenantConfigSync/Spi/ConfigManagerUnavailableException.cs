namespace Billing.Config.TenantConfigSync.Spi;

/// <summary>
/// Thrown when config-manager — the single source of truth for a tenant's config — cannot be read
/// (unreachable, error status, empty/invalid body, or no such tenant). It is fatal on purpose: the
/// system fails fast rather than serving an empty or partial context that would silently mis-rate.
/// </summary>
public sealed class ConfigManagerUnavailableException : Exception
{
    public string TenantName { get; }

    public ConfigManagerUnavailableException(string tenantName, string message, Exception? inner = null)
        : base(message, inner) => TenantName = tenantName;
}
