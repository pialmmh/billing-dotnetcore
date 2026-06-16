using Billing.Config.TenantConfigSync.Model;

namespace Billing.Config.TenantConfigSync.Internal;

/// <summary>Fills a freshly-loaded tree's computed lookups: one shared dbName→Tenant index across the
/// whole tree (O(1) resolution), and each node's leaf→root ancestor chain (the reseller charge chain).
/// Mirrors routesphere's rebuildTenantIndex + computeAncestorChains.</summary>
internal static class TenantTreeBuilder
{
    public static void Finalize(Tenant root)
    {
        var index = new Dictionary<string, Tenant>();
        Collect(root, index);
        foreach (var t in index.Values) t.Index = index;   // same reference shared across the tree
        AssignChains(root, pathRootToParent: []);
    }

    private static void Collect(Tenant t, Dictionary<string, Tenant> index)
    {
        index[t.DbName] = t;
        foreach (var child in t.Children.Values) Collect(child, index);
    }

    private static void AssignChains(Tenant t, IReadOnlyList<Tenant> pathRootToParent)
    {
        var pathRootToHere = new List<Tenant>(pathRootToParent) { t };
        var leafToRoot = new List<Tenant>(pathRootToHere);
        leafToRoot.Reverse();
        t.AncestorChain = leafToRoot;
        foreach (var child in t.Children.Values) AssignChains(child, pathRootToHere);
    }
}
