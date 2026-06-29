package com.telcobright.billing.tenantconfigsync.api;

import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.Collection;
import java.util.List;

/**
 * The read side of the tenant config cache. Callers (the gRPC handlers, the rater) resolve a
 * tenant by dbName and walk its ancestor chain; they never touch loading or Kafka. The snapshot
 * is swapped atomically on reload, so a returned {@link Tenant} is a consistent point-in-time view.
 *
 * <p>Faithful-port note: the C# get-only properties {@code IsLoaded} and {@code Roots} become
 * no-arg methods (Java interfaces cannot declare property getters); names kept verbatim.
 */
public interface ITenantRegistry {
    /** True once the first successful load has populated the registry. */
    boolean IsLoaded();

    /** Resolve a tenant by its globally-unique dbName, or null if unknown. */
    Tenant FindByDbName(String dbName);

    /** [leaf, …, root] for the tenant, or empty if unknown. The reseller charge chain. */
    List<Tenant> AncestorChain(String dbName);

    /** The loaded root tenants (one per enabled top-level tenant). */
    Collection<Tenant> Roots();
}
