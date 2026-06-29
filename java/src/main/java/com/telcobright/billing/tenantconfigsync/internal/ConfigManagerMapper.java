package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.Rule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.validation.IValidationRule;
import com.telcobright.billing.mediation.validation.ValidationRuleRegistry;
import com.telcobright.billing.tenantconfigsync.internal.dto.DynamicContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.MediationContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RuleRefDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.ServiceGroupConfigDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.DynamicContext;
import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the immutable Model (Tenant tree + DynamicContext + MediationContext) from the
 * config-manager wire DTOs. One reason to change: the wire shape. Computed lookups (index, ancestor
 * chains) are added afterwards by {@link TenantTreeBuilder}.
 */
final class ConfigManagerMapper {

    private ConfigManagerMapper() {
    }

    public static Tenant ToTenant(TenantDto dto) {
        Tenant t = new Tenant();
        t.Name = dto.Name != null ? dto.Name : "";
        t.DbName = dto.DbName != null ? dto.DbName : "";
        t.Parent = dto.Parent;
        t.Children = dto.Children == null
            ? new HashMap<>()
            : dto.Children.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ToTenant(e.getValue())));
        t.Context = ToContext(dto.Context);
        return t;
    }

    private static DynamicContext ToContext(DynamicContextDto dto) {
        if (dto == null) {
            return DynamicContext.Empty;
        }
        DynamicContext ctx = new DynamicContext();
        ctx.Partners = dto.Partners != null ? dto.Partners : new HashMap<>();
        ctx.RatePlans = dto.RatePlans != null ? dto.RatePlans : new HashMap<>();
        ctx.RatePlanWiseTodaysRates = dto.RatePlanWiseTodaysRates != null
            ? new HashMap<>(dto.RatePlanWiseTodaysRates)
            : new HashMap<>();
        ctx.RateAssignsCustomer = dto.RateAssignsCustomer != null ? dto.RateAssignsCustomer : List.of();
        ctx.RateAssignsSupplier = dto.RateAssignsSupplier != null ? dto.RateAssignsSupplier : List.of();
        ctx.PartnerIdWisePackageAccounts = dto.PartnerIdWisePackageAccounts != null
            ? new HashMap<>(dto.PartnerIdWisePackageAccounts)
            : new HashMap<>();
        ctx.MediationContext = ToMediation(dto.MediationContext);
        return ctx;
    }

    private static MediationContext ToMediation(MediationContextDto dto) {
        if (dto == null) {
            return MediationContext.Empty;
        }
        // The resolver (which tuples apply) and the per-day RateCache (their rates) are both derived from the
        // tenant's verbatim legacy rateplanassignmenttuples (each carrying its rateassigns); the legacy
        // PrefixMatcher longest-prefixes over the RateCache at charge time. The SG configs + checklists carry
        // rating-rule DATA and validation-rule REFERENCES — the references are bound to behaviour here.
        Map<Integer, ServiceGroupConfiguration> sgConfigs = dto.ServiceGroupConfigurations == null
            ? null
            : dto.ServiceGroupConfigurations.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ToSgConfig(e.getValue())));
        List<rateplanassignmenttuple> tuples =
            dto.RatePlanAssignmentTuples != null ? dto.RatePlanAssignmentTuples : List.of();
        return MediationContext.ForRating(
            tuples,
            dto.Categories,
            dto.ServiceGroupRules,
            sgConfigs,                            // null → the built-in default SG configs
            ToChecklist(dto.CommonChecklist));
    }

    private static ServiceGroupConfiguration ToSgConfig(ServiceGroupConfigDto dto) {
        List<Rule> rules = dto.Rules == null
            ? List.of()
            : dto.Rules.stream()
                .map(r -> (Rule) new RatingRule(r.IdServiceFamily, r.AssignDirection, r.DigitRulesData))
                .collect(Collectors.toList());
        return new ServiceGroupConfiguration(
            dto.ServiceGroupId,
            dto.Disabled,
            rules,
            ToChecklist(dto.AnsweredChecklist),
            ToChecklist(dto.UnansweredChecklist));
    }

    // Bind validation-rule REFERENCES (name + optional data) to behaviour via the registry; unknown name
    // throws at config-load (fail-fast), not at mediation time.
    private static List<IValidationRule<cdr>> ToChecklist(List<RuleRefDto> refs) {
        if (refs == null) {
            return List.of();
        }
        return refs.stream()
            .map(r -> ValidationRuleRegistry.Default.Resolve(r.Rule != null ? r.Rule : "", r.Data))
            .collect(Collectors.toList());
    }
}
