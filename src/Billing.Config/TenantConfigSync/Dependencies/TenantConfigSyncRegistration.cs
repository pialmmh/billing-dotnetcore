using Billing.Config.TenantConfigSync.Api;
using Billing.Config.TenantConfigSync.Internal;
using Billing.Config.TenantConfigSync.Spi;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace Billing.Config.TenantConfigSync.Dependencies;

/// <summary>The one wiring entry point. The host calls AddTenantConfigSync with the parsed options +
/// tenant selection; it provides the config-event source (Kafka) separately as an
/// <see cref="IConfigEventSource"/>. Everything is injected — nothing is service-located.</summary>
public static class TenantConfigSyncRegistration
{
    public static IServiceCollection AddTenantConfigSync(this IServiceCollection services,
        TenantConfigSyncOptions options, TenantSelection selection)
    {
        services.AddSingleton(Options.Create(options));
        services.AddSingleton(selection);

        services.AddHttpClient<IConfigManagerClient, HttpConfigManagerClient>();

        services.AddSingleton<TenantRegistryState>();
        services.AddSingleton<ITenantRegistry>(sp => sp.GetRequiredService<TenantRegistryState>());
        services.AddSingleton<TenantHierarchyLoader>();
        services.AddSingleton<DebouncedReloader>();

        services.AddHostedService<TenantConfigHostedService>();
        services.AddHostedService<DayBoundaryRefresher>();
        return services;
    }
}
