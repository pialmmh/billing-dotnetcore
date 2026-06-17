using Billing.Config.TenantConfigSync.Dependencies;
using Billing.Config.TenantConfigSync.Internal;
using Billing.Config.TenantConfigSync.Model;
using Billing.Config.TenantConfigSync.Publishes;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace Billing.Tests;

/// <summary>Fail-fast: if config-manager is unavailable, the loader throws and does NOT swap the
/// registry — never serve an empty/partial context that would silently mis-rate.</summary>
public class LoaderFailFastTests
{
    private sealed class ThrowingClient : IConfigManagerClient
    {
        public Task<Tenant> GetTenantRootAsync(string tenantName, CancellationToken ct) =>
            throw new ConfigManagerUnavailableException(tenantName, "config-manager down (test)");
    }

    private static TenantHierarchyLoader NewLoader(TenantRegistryState registry) => new(
        new ThrowingClient(),
        new TenantSelection { Tenants = [new SelectedTenant("ccl", true, "dev")] },
        registry,
        Options.Create(new TenantConfigSyncOptions()),
        NullLogger<TenantHierarchyLoader>.Instance);

    [Fact]
    public async Task Throws_and_does_not_swap_when_config_manager_unavailable()
    {
        var registry = new TenantRegistryState();
        var loader = NewLoader(registry);

        await Assert.ThrowsAsync<ConfigManagerUnavailableException>(() =>
            loader.LoadAllAsync(ConfigReloadTrigger.Startup, eventId: null, CancellationToken.None));

        Assert.False(registry.IsLoaded);          // no empty snapshot was swapped in
        Assert.Empty(registry.Roots);
    }
}
