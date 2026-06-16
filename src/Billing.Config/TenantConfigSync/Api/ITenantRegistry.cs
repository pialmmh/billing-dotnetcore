using Billing.Config.TenantConfigSync.Model;

namespace Billing.Config.TenantConfigSync.Api;

/// <summary>
/// The read side of the tenant config cache. Callers (the gRPC handlers, the rater) resolve a
/// tenant by dbName and walk its ancestor chain; they never touch loading or Kafka. The snapshot
/// is swapped atomically on reload, so a returned <see cref="Tenant"/> is a consistent point-in-time view.
/// </summary>
public interface ITenantRegistry
{
    /// <summary>True once the first successful load has populated the registry.</summary>
    bool IsLoaded { get; }

    /// <summary>Resolve a tenant by its globally-unique dbName, or null if unknown.</summary>
    Tenant? FindByDbName(string dbName);

    /// <summary>[leaf, …, root] for the tenant, or empty if unknown. The reseller charge chain.</summary>
    IReadOnlyList<Tenant> AncestorChain(string dbName);

    /// <summary>The loaded root tenants (one per enabled top-level tenant).</summary>
    IReadOnlyCollection<Tenant> Roots { get; }
}
