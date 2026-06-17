using Billing.Config.TenantConfigSync.Model;

namespace Billing.Config.TenantConfigSync.Spi;

/// <summary>
/// What this package REQUIRES to fetch config: a client to config-manager. The data side of the
/// world — the per-tenant DynamicContext (+ MediationContext) always arrives over HTTP here, never
/// over Kafka. The default HTTP implementation lives in Internal; tests inject a fake.
/// </summary>
public interface IConfigManagerClient
{
    /// <summary>
    /// Fetch one tenant's root (the nested tree + each node's DynamicContext) from
    /// <c>POST /get-specific-tenant-root?name=&lt;tenant&gt;</c>. Throws
    /// <see cref="ConfigManagerUnavailableException"/> if config-manager is unreachable, returns an
    /// error/empty body, or has no such tenant — fail-fast, because an empty context would silently
    /// mis-rate. Never returns null.
    /// </summary>
    Task<Tenant> GetTenantRootAsync(string tenantName, CancellationToken ct);
}
