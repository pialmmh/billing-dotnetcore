package com.telcobright.billing.tenantconfigsync.internal;

import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.tenantconfigsync.internal.dto.DynamicContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.MediationContextDto;
import com.telcobright.billing.tenantconfigsync.internal.dto.TenantDto;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
