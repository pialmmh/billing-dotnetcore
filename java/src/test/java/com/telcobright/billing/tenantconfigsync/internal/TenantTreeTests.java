package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.ServiceCategory;
import com.telcobright.billing.tenantconfigsync.model.DynamicContext;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tree logic: the shared dbName index and the leaf→root ancestor chain (the reseller charge chain) — the
 * parts that resolve a call to its tiers. No config-manager/Kafka needed.
 */
class TenantTreeTests {

    // admin (telcobright) → res1 (res_203) → res2 (res_205)
    private static Tenant BuildChain() {
        Tenant leaf = new Tenant();
        leaf.Name = "res2";
        leaf.DbName = "res_205";
        leaf.Parent = "res_203";

        Tenant mid = new Tenant();
        mid.Name = "res1";
        mid.DbName = "res_203";
        mid.Parent = "telcobright";
        mid.Children = new HashMap<>(Map.of("res_205", leaf));

        Tenant root = new Tenant();
        root.Name = "admin";
        root.DbName = "telcobright";
        root.Children = new HashMap<>(Map.of("res_203", mid));

        TenantTreeBuilder.Finalize(root);
        return root;
    }

    @Test
    void Index_covers_every_node_and_is_shared() {
        Tenant root = BuildChain();
        assertEquals(3, root.Index.size());
        assertTrue(root.Index.containsKey("telcobright"));
        assertTrue(root.Index.containsKey("res_203"));
        assertTrue(root.Index.containsKey("res_205"));
        // same shared reference across the tree
        assertSame(root.Index, root.Index.get("res_205").Index);
    }

    @Test
    void AncestorChain_runs_leaf_to_root() {
        Tenant root = BuildChain();
        Tenant leaf = root.Index.get("res_205");
        assertEquals(List.of("res_205", "res_203", "telcobright"),
                leaf.AncestorChain.stream().map(t -> t.DbName).collect(Collectors.toList()));
        // root's own chain is just itself
        assertEquals(List.of("telcobright"),
                root.AncestorChain.stream().map(t -> t.DbName).collect(Collectors.toList()));
    }

    @Test
    void Registry_swaps_and_resolves() {
        TenantRegistryState reg = new TenantRegistryState();
        assertFalse(reg.IsLoaded());

        reg.Swap(List.of(BuildChain()));

        assertTrue(reg.IsLoaded());
        assertEquals("res_205", reg.FindByDbName("res_205").DbName);
        assertNull(reg.FindByDbName("does_not_exist"));
        assertEquals(List.of("res_205", "res_203", "telcobright"),
                reg.AncestorChain("res_205").stream().map(t -> t.DbName).collect(Collectors.toList()));
        assertTrue(reg.AncestorChain("does_not_exist").isEmpty());
    }

    @Test
    void MediationContext_is_folded_inside_DynamicContext() {
        Tenant tenant = new Tenant();
        tenant.Name = "admin";
        tenant.DbName = "telcobright";
        DynamicContext ctx = new DynamicContext();
        MediationContext mc = new MediationContext();
        mc.Categories = new HashMap<>(Map.of(1, new ServiceCategory(1, "VOICE")));
        ctx.MediationContext = mc;
        tenant.Context = ctx;

        assertEquals("VOICE", tenant.Context.MediationContext.Categories.get(1).Type());
        assertSame(MediationContext.Empty, DynamicContext.Empty.MediationContext);
    }
}
