using Billing.Config.TenantConfigSync.Internal;
using Billing.Config.TenantConfigSync.Internal.Dto;
using Billing.Mediation.Validation;
using MediationModel;

namespace Billing.Tests;

/// <summary>The data-driven config path that replaces the legacy MEF/Spring.NET rule composition: validation
/// rules are BEHAVIOUR in a name-keyed registry, and config-manager sends only rule REFERENCES (name + data).
/// The mapper binds them, fail-fast on an unknown name.</summary>
public class ValidationRuleConfigTests
{
    [Fact]
    public void Registry_resolves_known_rules_and_fails_fast_on_unknown()
    {
        var rule = ValidationRuleRegistry.Default.Resolve("InPartnerIdGt0");
        Assert.True(rule.Validate(new cdr { InPartnerId = 5 }));
        Assert.False(rule.Validate(new cdr { InPartnerId = 0 }));

        var threshold = ValidationRuleRegistry.Default.Resolve("InPartnerCostGt", 1.0m);   // data-parameterised
        Assert.True(threshold.Validate(new cdr { InPartnerCost = 2.0m }));
        Assert.False(threshold.Validate(new cdr { InPartnerCost = 0.5m }));

        Assert.Throws<InvalidOperationException>(() => ValidationRuleRegistry.Default.Resolve("NopeRule"));
    }

    [Fact]
    public void Checklists_load_from_config_as_rule_references()
    {
        var dto = new TenantDto
        {
            Name = "admin", DbName = "telcobright",
            Context = new DynamicContextDto
            {
                MediationContext = new MediationContextDto
                {
                    ServiceGroupConfigurations = new Dictionary<int, ServiceGroupConfigDto>
                    {
                        [10] = new()
                        {
                            ServiceGroupId = 10,
                            Rules = new List<RatingRuleDto> { new() { IdServiceFamily = 10, AssignDirection = 1 } },
                            AnsweredChecklist = new List<RuleRefDto>
                            {
                                new() { Rule = "InPartnerIdGt0" },
                                new() { Rule = "InPartnerCostGt", Data = 0.0m },
                            },
                        },
                    },
                    CommonChecklist = new List<RuleRefDto> { new() { Rule = "ServiceGroupGt0" } },
                },
            },
        };

        var med = ConfigManagerMapper.ToTenant(dto).Context.MediationContext;

        Assert.Single(med.CommonChecklist);                            // common checklist bound from the ref
        var sg10 = med.ServiceGroupConfigurations[10];
        Assert.Single(sg10.Rules);                                     // rating-rule data survived
        Assert.Equal(2, sg10.AnsweredChecklist.Count);                 // two refs -> two real rules
        Assert.Empty(sg10.UnansweredChecklist);

        // and the resolved rules actually validate
        Assert.False(sg10.AnsweredChecklist[0].Validate(new cdr { InPartnerId = 0 }));
        Assert.True(sg10.AnsweredChecklist[0].Validate(new cdr { InPartnerId = 5 }));
    }

    [Fact]
    public void Unknown_rule_in_config_fails_the_load()
    {
        var dto = new TenantDto
        {
            Name = "admin", DbName = "telcobright",
            Context = new DynamicContextDto
            {
                MediationContext = new MediationContextDto
                {
                    CommonChecklist = new List<RuleRefDto> { new() { Rule = "DoesNotExist" } },
                },
            },
        };

        Assert.Throws<InvalidOperationException>(() => ConfigManagerMapper.ToTenant(dto));
    }
}
