package com.telcobright.billing.tenantconfigsync.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A node in the multi-tenant tree — the .NET mirror of routesphere's {@code Tenant}.
 * The immutable data (name, dbName, parent, children, context) comes from config-manager;
 * the computed lookups ({@link #Index}, {@link #AncestorChain}) are filled once,
 * after the tree is loaded, by the tree builder in this package's Internal layer.
 *
 * <p><b>dbName</b> is the globally-unique key (admin = the operator DB, resellers = res_NNN);
 * idPartner is unique only within a DB, so the chain is keyed by dbName.
 *
 * <p>Faithful-port note: C# {@code required}/{@code init}/{@code internal set} properties become
 * public mutable fields (the mapper sets Name/DbName/Parent/Children/Context; the tree builder
 * later writes Index/AncestorChain). Field names kept verbatim.
 */
public final class Tenant {
    public String Name;
    public String DbName;

    /** Parent dbName; null = root (admin/operator). */
    public String Parent;

    public Map<String, Tenant> Children = new HashMap<>();

    /** This tenant's config snapshot — holds the MediationContext. */
    public DynamicContext Context = DynamicContext.Empty;

    // --- computed after load (one shared index across the whole tree; O(1) dbName lookup) ---

    /** dbName → Tenant, shared across the whole tree. Set by the tree builder. */
    public Map<String, Tenant> Index = new HashMap<>();

    /** [this, parent, …, root]. Set by the tree builder; the reseller charge chain. */
    public List<Tenant> AncestorChain = List.of();
}
