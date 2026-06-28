namespace Billing.Config.TenantConfigSync.Model;

/// <summary>
/// A node in the multi-tenant tree — the .NET mirror of routesphere's <c>Tenant</c>.
/// The immutable data (name, dbName, parent, children, context) comes from config-manager;
/// the computed lookups (<see cref="Index"/>, <see cref="AncestorChain"/>) are filled once,
/// after the tree is loaded, by the tree builder in this package's Internal layer.
///
/// <para><b>dbName</b> is the globally-unique key (admin = the operator DB, resellers = res_NNN);
/// idPartner is unique only within a DB, so the chain is keyed by dbName.</para>
/// </summary>
public sealed class Tenant
{
    public required string Name { get; init; }
    public required string DbName { get; init; }

    /// <summary>Parent dbName; null = root (admin/operator).</summary>
    public string? Parent { get; init; }

    public IReadOnlyDictionary<string, Tenant> Children { get; init; }
        = new Dictionary<string, Tenant>();

    /// <summary>This tenant's config snapshot — holds the MediationContext.</summary>
    public DynamicContext Context { get; init; } = DynamicContext.Empty;

    // --- computed after load (one shared index across the whole tree; O(1) dbName lookup) ---

    /// <summary>dbName → Tenant, shared across the whole tree. Set by the tree builder.</summary>
    public IReadOnlyDictionary<string, Tenant> Index { get; internal set; }
        = new Dictionary<string, Tenant>();

    /// <summary>[this, parent, …, root]. Set by the tree builder; the reseller charge chain.</summary>
    public IReadOnlyList<Tenant> AncestorChain { get; internal set; } = [];
}
