package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.PrefixMatcher;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;
import com.telcobright.billing.tenantconfigsync.internal.dto.DynamicContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.MediationContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config-manager → Model mapping, focused on the rating wiring: a tenant's legacy
 * rateplanassignmenttuples must arrive as a working RatePlanResolver folded inside its MediationContext.
 */
class ConfigManagerMapperTests {

    private static TenantDto TenantWith(MediationContextDto mediation) {
        TenantDto dto = new TenantDto();
        dto.Name = "admin";
        dto.DbName = "telcobright";
        DynamicContextDto ctx = new DynamicContextDto();
        ctx.MediationContext = mediation;
        dto.Context = ctx;
        return dto;
    }

    @Test
    void RatePlanResolver_is_built_from_the_tuples() {
        MediationContextDto mediation = new MediationContextDto();
        mediation.RatePlanAssignmentTuples = List.of(
                TestData.Tup(10, AssignmentDirection.Customer.value, 5, null, 0,
                        TestData.Ra(1712, "1.0").idRatePlan(7)));

        Tenant tenant = ConfigManagerMapper.ToTenant(TenantWith(mediation));
        List<rateplanassignmenttuple> tuples =
                tenant.Context.MediationContext.RatePlanResolver.Resolve(10, 1, 5, null);

        assertEquals(1, tuples.size());
        assertEquals(7, (int) (long) tuples.get(0).rateassigns.get(0).idrateplan);
    }

    @Test
    void Empty_context_yields_an_empty_resolver() {
        TenantDto dto = new TenantDto();
        dto.Name = "admin";
        dto.DbName = "telcobright";
        Tenant tenant = ConfigManagerMapper.ToTenant(dto);
        assertTrue(tenant.Context.MediationContext.RatePlanResolver.Resolve(10, 1, 5, null).isEmpty());
    }

    @Test
    void Flat_customer_rateassigns_become_a_default_plan_and_price_by_prefix() {
        // The reseller model: config-manager serves the tenant's customer rates FLAT on DynamicContext
        // (rateAssignsCustomer, no per-partner ratePlanAssignmentTuples). Billing must still price off them.
        TenantDto dto = new TenantDto();
        dto.Name = "res_233";
        dto.DbName = "res_233";
        DynamicContextDto ctx = new DynamicContextDto();
        ctx.RateAssignsCustomer = List.of(
                TestData.Ra(880, "0.35").build(),
                TestData.Ra(8801, "0.40").build());
        dto.Context = ctx;

        Tenant tenant = ConfigManagerMapper.ToTenant(dto);
        MediationContext mc = tenant.Context.MediationContext;

        // The resolver returns the tenant's default customer plan for ANY partner (none were served as tuples).
        List<rateplanassignmenttuple> tuples =
                mc.RatePlanResolver.Resolve(10, AssignmentDirection.Customer.value, 999, null);
        assertFalse(tuples.isEmpty());

        // …and the RateCache prices the dialed number by longest-prefix over those rates.
        LocalDateTime answer = LocalDateTime.of(2026, 6, 19, 12, 0);
        DateRange day = new DateRange(answer.toLocalDate().atStartOfDay(), answer.toLocalDate().atStartOfDay().plusDays(1));
        List<TupleByPeriod> tups = tuples.stream().map(t -> {
            TupleByPeriod tp = new TupleByPeriod();
            tp.IdAssignmentTuple = t.id;
            tp.DRange = day;
            tp.Priority = t.priority;
            return tp;
        }).collect(Collectors.toList());

        rateassign longest = new PrefixMatcher(mc.RateCache, "88017123456", 1, 1, tups, answer).MatchPrefix();
        assertEquals(8801, longest.Prefix);                                       // longest prefix wins
        assertEquals(0, new BigDecimal("0.40").compareTo(longest.rateamount));

        rateassign shorter = new PrefixMatcher(mc.RateCache, "88099999999", 1, 1, tups, answer).MatchPrefix();
        assertEquals(880, shorter.Prefix);
        assertEquals(0, new BigDecimal("0.35").compareTo(shorter.rateamount));
    }
}
