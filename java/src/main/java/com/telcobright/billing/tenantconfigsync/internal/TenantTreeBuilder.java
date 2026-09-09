package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
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
 *
 * <p>It also marks each node's {@code MediationContext.IsResellerTier} — a node is a reseller tier iff it has a
 * parent, which is exactly the tree position this class already computes. Derived here rather than from the wire
 * DTO so it cannot disagree with the chain the charge path walks.
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
        // Has a parent -> a reseller tier. The root (empty path) keeps the flag false. NEVER written through
        // MediationContext.Empty: a tenant served with no context falls back to that SINGLETON (see
        // DynamicContext.Empty), so flagging it would mark every default context in the process as a reseller.
        // Such a tenant carries no rate config and rates nothing, so it has nothing to flag.
        if (!pathRootToParent.isEmpty() && t.Context != null
                && t.Context.MediationContext != null && t.Context.MediationContext != MediationContext.Empty) {
            t.Context.MediationContext.IsResellerTier = true;
        }
        for (Tenant child : t.Children.values()) {
            AssignChains(child, pathRootToHere);
        }
    }
}
