package com.telcobright.billing.tenantconfigsync.dependencies;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The parsed tenants.yml — which tenants load and their active profiles.
 *
 * <p>Faithful-port note: C# {@code init} property → public field; the computed
 * {@code Enabled} property → the no-arg method {@link #Enabled()}.
 */
public final class TenantSelection {
    public List<SelectedTenant> Tenants = List.of();

    public List<SelectedTenant> Enabled() {
        return Tenants.stream().filter(SelectedTenant::Enabled).collect(Collectors.toList());
    }
}
