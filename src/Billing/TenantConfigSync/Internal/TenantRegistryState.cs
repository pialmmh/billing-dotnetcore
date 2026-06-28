using Billing.Config.TenantConfigSync.Api;
using Billing.Config.TenantConfigSync.Model;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>The live <see cref="ITenantRegistry"/>. Holds an immutable snapshot (roots + a global
/// dbName index across all roots) behind one volatile reference, so a reload swaps the whole view
/// atomically — readers never see a half-updated tree.</summary>
internal sealed class TenantRegistryState : ITenantRegistry
{
    private volatile Snapshot _snapshot = Snapshot.Empty;

    public bool IsLoaded => _snapshot.Loaded;
    public IReadOnlyCollection<Tenant> Roots => _snapshot.Roots;

    public Tenant? FindByDbName(string dbName) =>
        _snapshot.Index.TryGetValue(dbName, out var t) ? t : null;

    public IReadOnlyList<Tenant> AncestorChain(string dbName) =>
        FindByDbName(dbName)?.AncestorChain ?? [];

    /// <summary>Replace the whole view with the newly-loaded roots. Called by the loader only.</summary>
    internal void Swap(IReadOnlyList<Tenant> roots)
    {
        var index = new Dictionary<string, Tenant>();
        foreach (var root in roots)
            foreach (var kv in root.Index)      // each root carries its tree's shared index
                index[kv.Key] = kv.Value;
        _snapshot = new Snapshot(roots, index, Loaded: true);
    }

    private sealed record Snapshot(
        IReadOnlyCollection<Tenant> Roots,
        IReadOnlyDictionary<string, Tenant> Index,
        bool Loaded)
    {
        public static Snapshot Empty { get; } =
            new([], new Dictionary<string, Tenant>(), false);
    }
}
