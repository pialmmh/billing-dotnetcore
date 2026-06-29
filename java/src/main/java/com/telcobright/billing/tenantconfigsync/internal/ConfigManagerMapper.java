package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.context.RatingRule;
import com.telcobright.billing.mediation.context.Rule;
import com.telcobright.billing.mediation.context.ServiceGroupConfiguration;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.validation.IValidationRule;
import com.telcobright.billing.mediation.validation.ValidationRuleRegistry;
import com.telcobright.billing.tenantconfigsync.internal.dto.DynamicContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.MediationContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.RuleRefDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.ServiceGroupConfigDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.DynamicContext;
import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.ArrayList;
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
        ctx.MediationContext = ToMediation(dto.MediationContext, ctx.RateAssignsCustomer, ctx.RateAssignsSupplier);
        return ctx;
    }

    private static MediationContext ToMediation(MediationContextDto dto,
            List<rateassign> customerRates, List<rateassign> supplierRates) {
        Map<Integer, ServiceGroupConfiguration> sgConfigs = (dto == null || dto.ServiceGroupConfigurations == null)
            ? null
            : dto.ServiceGroupConfigurations.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> ToSgConfig(e.getValue())));

        // The resolver (which plan applies) + the per-day RateCache (the rates) are derived from this tenant's
        // rate-plan-assignment tuples; the legacy PrefixMatcher longest-prefixes over the RateCache at charge
        // time. Prefer the legacy tuples when config-manager serves them; otherwise wrap the tenant's flat
        // customer/supplier rateassigns (the reseller model: ONE plan per tier, served on DynamicContext as
        // rateAssignsCustomer/Supplier) as DEFAULT tuples so the resolver returns them for any partner — the
        // same A2ZRater path, just fed from the DynamicContext instead of per-partner tuples.
        List<rateplanassignmenttuple> tuples = new ArrayList<>();
        if (dto != null && dto.RatePlanAssignmentTuples != null)
            tuples.addAll(dto.RatePlanAssignmentTuples);
        if (tuples.isEmpty()) {
            if (customerRates != null && !customerRates.isEmpty())
                tuples.add(DefaultTuple(-1, AssignmentDirection.Customer.value, customerRates));
            if (supplierRates != null && !supplierRates.isEmpty())
                tuples.add(DefaultTuple(-2, AssignmentDirection.Supplier.value, supplierRates));
        }

        return MediationContext.ForRating(
            tuples,
            dto != null ? dto.Categories : null,
            dto != null ? dto.ServiceGroupRules : null,
            sgConfigs,                            // null → the built-in default SG configs
            dto != null ? ToChecklist(dto.CommonChecklist) : null);
    }

    // The tenant's single rate plan for a direction with no partner/route — RatePlanResolver returns it as
    // that direction's default (reseller model). Its rateassigns carry the dial-prefix rows the legacy rates on.
    private static rateplanassignmenttuple DefaultTuple(int id, int direction, List<rateassign> rates) {
        rateplanassignmenttuple t = new rateplanassignmenttuple();
        t.id = id;
        t.idService = 0;
        t.AssignDirection = direction;
        t.idpartner = null;
        t.route = null;
        t.priority = 0;
        t.rateassigns = rates;
        return t;
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
