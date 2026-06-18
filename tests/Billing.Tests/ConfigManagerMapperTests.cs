using Billing.Config.TenantConfigSync.Internal;
using Billing.Config.TenantConfigSync.Internal.Dto;
using Billing.Mediation.Context;
using Billing.Mediation.Model;

namespace Billing.Tests;

/// <summary>The config-manager → Model mapping, focused on the RateCache wiring: a tenant's
/// today's-rates map must arrive as a working longest-prefix <c>RateCache</c> folded inside its
/// MediationContext, stamped for today. Rates ride on DynamicContext (not MediationContextDto), so the
/// cache must be built even when the MediationContext block is absent.</summary>
public class ConfigManagerMapperTests
{
    private static TenantDto TenantWithRates(MediationContextDto? mediation) => new()
    {
        Name = "admin",
        DbName = "telcobright",
        Context = new DynamicContextDto
        {
            RatePlanWiseTodaysRates = new Dictionary<int, Dictionary<string, Rate>>
            {
                [7] = new()
                {
                    ["880"]   = new() { Prefix = "880",   RateAmount = 1.0m, IdRatePlan = 7 },
                    ["8801"]  = new() { Prefix = "8801",  RateAmount = 2.0m, IdRatePlan = 7 },
                    ["88017"] = new() { Prefix = "88017", RateAmount = 3.0m, IdRatePlan = 7 },
                },
            },
            MediationContext = mediation,
        },
    };

    [Fact]
    public void RateCache_is_built_and_does_longest_prefix_lookup()
    {
        var tenant = ConfigManagerMapper.ToTenant(TenantWithRates(mediation: null));
        var cache = tenant.Context.MediationContext.RateCache;

        Assert.Equal(3.0m, cache.FindRate(7, "8801712345")!.RateAmount);   // 88017 (longest)
        Assert.Equal(2.0m, cache.FindRate(7, "8801812345")!.RateAmount);   // 8801, not 88017
        Assert.Equal(1.0m, cache.FindRate(7, "8809912345")!.RateAmount);   // 880
        Assert.Null(cache.FindRate(7, "9990000"));                         // no prefix matches
        Assert.Null(cache.FindRate(99, "8801712"));                        // unknown plan
    }

    [Fact]
    public void RateCache_is_stamped_for_today()
    {
        var tenant = ConfigManagerMapper.ToTenant(TenantWithRates(mediation: null));
        Assert.False(tenant.Context.MediationContext.RateCache.IsStale(DateOnly.FromDateTime(DateTime.Today)));
    }

    [Fact]
    public void RateCache_built_even_when_mediation_block_present()
    {
        // Categories present, but the cache still comes from the DynamicContext-level today's-rates.
        var mediation = new MediationContextDto
        {
            Categories = new Dictionary<int, ServiceCategory> { [1] = new() { Id = 1, Type = "VOICE" } },
        };
        var tenant = ConfigManagerMapper.ToTenant(TenantWithRates(mediation));

        Assert.Equal("VOICE", tenant.Context.MediationContext.Categories[1].Type);
        Assert.Equal(3.0m, tenant.Context.MediationContext.RateCache.FindRate(7, "8801712345")!.RateAmount);
    }

    [Fact]
    public void Empty_context_yields_an_empty_but_usable_RateCache()
    {
        // No Context at all → DynamicContext.Empty, whose MediationContext.RateCache is the empty cache.
        var tenant = ConfigManagerMapper.ToTenant(new TenantDto { Name = "admin", DbName = "telcobright" });
        Assert.Null(tenant.Context.MediationContext.RateCache.FindRate(7, "8801712"));
    }
}
