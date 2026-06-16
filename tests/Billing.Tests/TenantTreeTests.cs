using Billing.Config.TenantConfigSync.Internal;
using Billing.Config.TenantConfigSync.Model;
using Billing.Mediation.Context;

namespace Billing.Tests;

/// <summary>Tree logic: the shared dbName index and the leaf→root ancestor chain (the reseller charge
/// chain) — the parts that resolve a call to its tiers. No config-manager/Kafka needed.</summary>
public class TenantTreeTests
{
    // admin (telcobright) → res1 (res_203) → res2 (res_205)
    private static Tenant BuildChain()
    {
        var leaf = new Tenant { Name = "res2", DbName = "res_205", Parent = "res_203" };
        var mid = new Tenant
        {
            Name = "res1",
            DbName = "res_203",
            Parent = "telcobright",
            Children = new Dictionary<string, Tenant> { ["res_205"] = leaf },
        };
        var root = new Tenant
        {
            Name = "admin",
            DbName = "telcobright",
            Children = new Dictionary<string, Tenant> { ["res_203"] = mid },
        };
        TenantTreeBuilder.Finalize(root);
        return root;
    }

    [Fact]
    public void Index_covers_every_node_and_is_shared()
    {
        var root = BuildChain();
        Assert.Equal(3, root.Index.Count);
        Assert.True(root.Index.ContainsKey("telcobright"));
        Assert.True(root.Index.ContainsKey("res_203"));
        Assert.True(root.Index.ContainsKey("res_205"));
        // same shared reference across the tree
        Assert.Same(root.Index, root.Index["res_205"].Index);
    }

    [Fact]
    public void AncestorChain_runs_leaf_to_root()
    {
        var root = BuildChain();
        var leaf = root.Index["res_205"];
        Assert.Equal(new[] { "res_205", "res_203", "telcobright" },
            leaf.AncestorChain.Select(t => t.DbName).ToArray());
        // root's own chain is just itself
        Assert.Equal(new[] { "telcobright" }, root.AncestorChain.Select(t => t.DbName).ToArray());
    }

    [Fact]
    public void Registry_swaps_and_resolves()
    {
        var reg = new TenantRegistryState();
        Assert.False(reg.IsLoaded);

        reg.Swap(new[] { BuildChain() });

        Assert.True(reg.IsLoaded);
        Assert.Equal("res_205", reg.FindByDbName("res_205")!.DbName);
        Assert.Null(reg.FindByDbName("does_not_exist"));
        Assert.Equal(new[] { "res_205", "res_203", "telcobright" },
            reg.AncestorChain("res_205").Select(t => t.DbName).ToArray());
        Assert.Empty(reg.AncestorChain("does_not_exist"));
    }

    [Fact]
    public void MediationContext_is_folded_inside_DynamicContext()
    {
        var tenant = new Tenant
        {
            Name = "admin",
            DbName = "telcobright",
            Context = new DynamicContext
            {
                MediationContext = new MediationContext
                {
                    Categories = new Dictionary<int, ServiceCategory> { [1] = new() { Id = 1, Type = "VOICE" } },
                },
            },
        };

        Assert.Equal("VOICE", tenant.Context.MediationContext.Categories[1].Type);
        Assert.Same(MediationContext.Empty, DynamicContext.Empty.MediationContext);
    }
}
