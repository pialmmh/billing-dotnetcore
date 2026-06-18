using Microsoft.Extensions.DependencyInjection;

namespace Billing.Mediation.ServiceGroups;

/// <summary>DI registration for service-group detection. Registers each detector as an
/// <see cref="IServiceGroupDetector"/> singleton plus the <see cref="ServiceGroupDetection"/>
/// coordinator (which the container constructs from the registered detectors). Add a new service
/// group by registering its detector before resolving the coordinator.</summary>
public static class ServiceGroupServiceCollectionExtensions
{
    public static IServiceCollection AddServiceGroupDetection(this IServiceCollection services)
    {
        services.AddSingleton<IServiceGroupDetector, SgDomOffnetOut>();
        services.AddSingleton<IServiceGroupDetector, SgDomOffnetIn>();
        services.AddSingleton<ServiceGroupDetection>();
        return services;
    }
}
