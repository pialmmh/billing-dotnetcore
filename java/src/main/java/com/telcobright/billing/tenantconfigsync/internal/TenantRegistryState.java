package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live {@link ITenantRegistry}. Holds an immutable snapshot (roots + a global dbName index across
 * all roots) behind one volatile reference, so a reload swaps the whole view atomically — readers never
 * see a half-updated tree.
 *
 * <p>Faithful-port note: C# {@code internal} → Java {@code public} (the registration glue in another
 * package wires it). The C# get-only properties become no-arg methods.
 */
public final class TenantRegistryState implements ITenantRegistry {
    private volatile Snapshot _snapshot = Snapshot.Empty;

    public boolean IsLoaded() {
        return _snapshot.Loaded();
    }

    public Collection<Tenant> Roots() {
        return _snapshot.Roots();
    }

    public Tenant FindByDbName(String dbName) {
        return _snapshot.Index().get(dbName);
    }

    public List<Tenant> AncestorChain(String dbName) {
        Tenant t = FindByDbName(dbName);
        return t != null ? t.AncestorChain : List.of();
    }

    /** Replace the whole view with the newly-loaded roots. Called by the loader only. */
    void Swap(List<Tenant> roots) {
        Map<String, Tenant> index = new HashMap<>();
        for (Tenant root : roots) {
            for (Map.Entry<String, Tenant> kv : root.Index.entrySet()) {   // each root carries its tree's shared index
                index.put(kv.getKey(), kv.getValue());
            }
        }
        _snapshot = new Snapshot(roots, index, true);
    }

    private record Snapshot(
        Collection<Tenant> Roots,
        Map<String, Tenant> Index,
        boolean Loaded) {

        static final Snapshot Empty = new Snapshot(List.of(), new HashMap<>(), false);
    }
}
