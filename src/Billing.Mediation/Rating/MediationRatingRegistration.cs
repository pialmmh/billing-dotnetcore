using Billing.Mediation.Rating.Internal;
using Microsoft.Extensions.DependencyInjection;

namespace Billing.Mediation.Rating;

/// <summary>Wiring for the rating engine. The host calls AddMediationRating; the stub rater is the
/// current <see cref="ITierRater"/> and gets swapped for the real one in place (one line) later.</summary>
public static class MediationRatingRegistration
{
    public static IServiceCollection AddMediationRating(this IServiceCollection services)
    {
        services.AddSingleton<ITierRater, StubTierRater>();
        services.AddSingleton<MaxRateEngine>();
        return services;
    }
}
