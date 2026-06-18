using Billing.Config.TenantConfigSync.Internal;
using Billing.Config.TenantConfigSync.Internal.Dto;
using Billing.Mediation.Context;
using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Tests;

/// <summary>The config-manager → Model mapping, focused on the rating wiring: a tenant's legacy
/// rateplanassignmenttuples must arrive as a working RatePlanResolver folded inside its MediationContext.</summary>
public class ConfigManagerMapperTests
{
    private static TenantDto TenantWith(MediationContextDto? mediation) => new()
    {
        Name = "admin",
        DbName = "telcobright",
        Context = new DynamicContextDto { MediationContext = mediation },
    };

    [Fact]
    public void RatePlanResolver_is_built_from_the_tuples()
    {
        var mediation = new MediationContextDto
        {
            RatePlanAssignmentTuples = new List<rateplanassignmenttuple>
            {
                TestData.Tup(10, (int)AssignmentDirection.Customer, 5, null, 0,
                    TestData.Ra(prefix: 1712, amount: 1.0m, idRatePlan: 7)),
            },
        };

        var tenant = ConfigManagerMapper.ToTenant(TenantWith(mediation));
        var tuples = tenant.Context.MediationContext.RatePlanResolver.Resolve(10, 1, idPartner: 5, route: null);

        Assert.Single(tuples);
        Assert.Equal(7, (int)tuples[0].rateassigns.First().idrateplan!);
    }

    [Fact]
    public void Empty_context_yields_an_empty_resolver()
    {
        var tenant = ConfigManagerMapper.ToTenant(new TenantDto { Name = "admin", DbName = "telcobright" });
        Assert.Empty(tenant.Context.MediationContext.RatePlanResolver.Resolve(10, 1, 5, null));
    }
}
