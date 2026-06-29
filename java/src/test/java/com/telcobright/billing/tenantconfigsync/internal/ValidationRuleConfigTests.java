package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.validation.ValidationRuleRegistry;
import com.telcobright.billing.tenantconfigsync.internal.dto.DynamicContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.MediationContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RatingRuleDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RuleRefDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.ServiceGroupConfigDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The data-driven config path that replaces the legacy MEF/Spring.NET rule composition: validation rules are
 * BEHAVIOUR in a name-keyed registry, and config-manager sends only rule REFERENCES (name + data). The mapper
 * binds them, fail-fast on an unknown name. Faithful port of ValidationRuleConfigTests.cs.
 *
 * <p>PACKAGE NOTE: the C# test lived in {@code Billing.Tests} and reached {@code internal ConfigManagerMapper}
 * via InternalsVisibleTo. The Java {@code ConfigManagerMapper} is package-private in
 * {@code tenantconfigsync.internal}, so per RULE T0 (same package for package-private access) this test is
 * placed HERE rather than in {@code mediation.validation} — that is the only package from which both
 * {@code ConfigManagerMapper} and the public {@code ValidationRuleRegistry} are reachable. C#
 * {@code InvalidOperationException} maps to {@link IllegalStateException}; {@code 1.0m}/{@code 0.0m} to
 * {@link BigDecimal} strings; record accessors take {@code ()}.</p>
 */
class ValidationRuleConfigTests {

    @Test
    void Registry_resolves_known_rules_and_fails_fast_on_unknown() {
        var rule = ValidationRuleRegistry.Default.Resolve("InPartnerIdGt0");
        cdr ok = new cdr();
        ok.InPartnerId = 5;
        assertTrue(rule.Validate(ok));
        cdr zero = new cdr();
        zero.InPartnerId = 0;
        assertFalse(rule.Validate(zero));

        // DurationSec-gated: at/above the threshold the field must be > 0; below it, must be 0.
        var gated = ValidationRuleRegistry.Default.Resolve("InPartnerCostGt0", new BigDecimal("1.0"));
        cdr above = new cdr();
        above.DurationSec = BigDecimal.valueOf(60);
        above.InPartnerCost = new BigDecimal("2.0");
        assertTrue(gated.Validate(above));
        cdr aboveZeroCost = new cdr();
        aboveZeroCost.DurationSec = BigDecimal.valueOf(60);
        aboveZeroCost.InPartnerCost = BigDecimal.ZERO;
        assertFalse(gated.Validate(aboveZeroCost));
        cdr below = new cdr();
        below.DurationSec = BigDecimal.ZERO;
        below.InPartnerCost = BigDecimal.ZERO;
        assertTrue(gated.Validate(below));   // below threshold -> must be 0

        assertThrows(IllegalStateException.class, () -> ValidationRuleRegistry.Default.Resolve("NopeRule"));
    }

    @Test
    void Checklists_load_from_config_as_rule_references() {
        TenantDto dto = new TenantDto();
        dto.Name = "admin";
        dto.DbName = "telcobright";

        DynamicContextDto context = new DynamicContextDto();
        MediationContextDto mediationDto = new MediationContextDto();

        ServiceGroupConfigDto sg = new ServiceGroupConfigDto();
        sg.ServiceGroupId = 10;
        RatingRuleDto ratingRule = new RatingRuleDto();
        ratingRule.IdServiceFamily = 10;
        ratingRule.AssignDirection = 1;
        sg.Rules = new ArrayList<>(List.of(ratingRule));
        RuleRefDto ans1 = new RuleRefDto();
        ans1.Rule = "InPartnerIdGt0";
        RuleRefDto ans2 = new RuleRefDto();
        ans2.Rule = "InPartnerCostGt0";
        ans2.Data = new BigDecimal("0.0");
        sg.AnsweredChecklist = new ArrayList<>(List.of(ans1, ans2));
        Map<Integer, ServiceGroupConfigDto> sgConfigs = new HashMap<>();
        sgConfigs.put(10, sg);
        mediationDto.ServiceGroupConfigurations = sgConfigs;

        RuleRefDto common = new RuleRefDto();
        common.Rule = "ServiceGroupGt0";
        mediationDto.CommonChecklist = new ArrayList<>(List.of(common));

        context.MediationContext = mediationDto;
        dto.Context = context;

        var med = ConfigManagerMapper.ToTenant(dto).Context.MediationContext;

        assertEquals(1, med.CommonChecklist.size());                            // common checklist bound from the ref
        var sg10 = med.ServiceGroupConfigurations.get(10);
        assertEquals(1, sg10.Rules().size());                                  // rating-rule data survived
        assertEquals(2, sg10.AnsweredChecklist().size());                      // two refs -> two real rules
        assertTrue(sg10.UnansweredChecklist().isEmpty());

        // and the resolved rules actually validate
        cdr zero = new cdr();
        zero.InPartnerId = 0;
        assertFalse(sg10.AnsweredChecklist().get(0).Validate(zero));
        cdr ok = new cdr();
        ok.InPartnerId = 5;
        assertTrue(sg10.AnsweredChecklist().get(0).Validate(ok));
    }

    @Test
    void Unknown_rule_in_config_fails_the_load() {
        TenantDto dto = new TenantDto();
        dto.Name = "admin";
        dto.DbName = "telcobright";

        DynamicContextDto context = new DynamicContextDto();
        MediationContextDto mediationDto = new MediationContextDto();
        RuleRefDto badRef = new RuleRefDto();
        badRef.Rule = "DoesNotExist";
        mediationDto.CommonChecklist = new ArrayList<>(List.of(badRef));
        context.MediationContext = mediationDto;
        dto.Context = context;

        assertThrows(IllegalStateException.class, () -> ConfigManagerMapper.ToTenant(dto));
    }
}
