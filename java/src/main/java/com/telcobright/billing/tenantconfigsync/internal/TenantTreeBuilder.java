package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills a freshly-loaded tree's computed lookups: one shared dbName→Tenant index across the
 * whole tree (O(1) resolution), and each node's leaf→root ancestor chain (the reseller charge chain).
 * Mirrors routesphere's rebuildTenantIndex + computeAncestorChains.
 */
final class TenantTreeBuilder {

    private TenantTreeBuilder() {
    }

    public static void Finalize(Tenant root) {
        Map<String, Tenant> index = new HashMap<>();
        Collect(root, index);
        for (Tenant t : index.values()) {
            t.Index = index;   // same reference shared across the tree
        }
        AssignChains(root, List.of());
    }

    private static void Collect(Tenant t, Map<String, Tenant> index) {
        index.put(t.DbName, t);
        for (Tenant child : t.Children.values()) {
            Collect(child, index);
        }
    }

    private static void AssignChains(Tenant t, List<Tenant> pathRootToParent) {
        List<Tenant> pathRootToHere = new ArrayList<>(pathRootToParent);
        pathRootToHere.add(t);
        List<Tenant> leafToRoot = new ArrayList<>(pathRootToHere);
        Collections.reverse(leafToRoot);
        t.AncestorChain = leafToRoot;
        for (Tenant child : t.Children.values()) {
            AssignChains(child, pathRootToHere);
        }
    }
}
