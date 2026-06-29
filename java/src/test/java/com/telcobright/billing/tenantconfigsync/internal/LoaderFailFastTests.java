package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.tenantconfigsync.dependencies.SelectedTenant;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantConfigSyncOptions;
import com.telcobright.billing.tenantconfigsync.dependencies.TenantSelection;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.tenantconfigsync.publishes.ConfigReloadTrigger;
import com.telcobright.billing.tenantconfigsync.spi.ConfigManagerUnavailableException;
import com.telcobright.billing.tenantconfigsync.spi.IConfigManagerClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fail-fast: if config-manager is unavailable, the loader throws and does NOT swap the registry — never
 * serve an empty/partial context that would silently mis-rate.
 *
 * <p>Faithful-port note: the ported {@link IConfigManagerClient}/{@link TenantHierarchyLoader} are
 * synchronous (RULE 2 — the C# {@code Task}/{@code CancellationToken} async surface was dropped), so the C#
 * {@code Assert.ThrowsAsync} + {@code await LoadAllAsync(...)} becomes {@code assertThrows} + {@code LoadAll};
 * the C# {@code IOptions} wrapper and {@code NullLogger} injection are gone (options passed directly, static
 * logger in the loader).</p>
 */
class LoaderFailFastTests {

    private static final class ThrowingClient implements IConfigManagerClient {
        @Override
        public Tenant GetTenantRoot(String tenantName) {
            throw new ConfigManagerUnavailableException(tenantName, "config-manager down (test)");
        }
    }

    private static TenantHierarchyLoader NewLoader(TenantRegistryState registry) {
        TenantSelection selection = new TenantSelection();
        selection.Tenants = List.of(new SelectedTenant("ccl", true, "dev"));
        return new TenantHierarchyLoader(
                new ThrowingClient(),
                selection,
                registry,
                new TenantConfigSyncOptions());
    }

    @Test
    void Throws_and_does_not_swap_when_config_manager_unavailable() {
        TenantRegistryState registry = new TenantRegistryState();
        TenantHierarchyLoader loader = NewLoader(registry);

        assertThrows(ConfigManagerUnavailableException.class, () ->
                loader.LoadAll(ConfigReloadTrigger.Startup, null));

        assertFalse(registry.IsLoaded());          // no empty snapshot was swapped in
        assertTrue(registry.Roots().isEmpty());
    }
}
