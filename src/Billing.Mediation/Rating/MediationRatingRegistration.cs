using Billing.Mediation.Rating.Internal;
using Billing.Mediation.ServiceGroups;
using Microsoft.Extensions.DependencyInjection;

namespace Billing.Mediation.Rating;

/// <summary>Wiring for the rating engine. The host calls AddMediationRating; the stub rater is the
/// current <see cref="ITierRater"/> and gets swapped for the real one in place (one line) later. The
/// finalize side (real, SG10/11-backed) is wired through <see cref="FinalizeEngine"/>.</summary>
public static class MediationRatingRegistration
{
    public static IServiceCollection AddMediationRating(this IServiceCollection services)
    {
        services.AddSingleton<ITierRater, StubTierRater>();
        services.AddSingleton<MaxRateEngine>();

        // Finalize (post-call charge): SG detection → BasicCharge → FinalizeEngine.
        services.AddServiceGroupDetection();
        services.AddSingleton<BasicCharge>();
        services.AddSingleton<FinalizeEngine>();
        return services;
    }
}
