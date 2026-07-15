package com.telcobright.billing.tenantconfigsync.internal.dto;

import com.telcobright.billing.mediation.model.PackageAccount;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.mediation.model.RatePlan;

import java.util.List;
import java.util.Map;

public final class DynamicContextDto {
    public Map<Integer, Partner> Partners;
    public Map<Integer, RatePlan> RatePlans;
    public Map<Integer, Map<String, Rate>> RatePlanWiseTodaysRates;
    /** Tomorrow's rates, served alongside today's so the RateCache keeps both realtime days warm. */
    public Map<Integer, Map<String, Rate>> RatePlanWiseTomorrowsRates;

    /**
     * The served rate-assign rows. config-manager serves each one's rate-plan-assignment tuple NESTED (see
     * {@link RateAssignDto}); the mapper groups them by that tuple id to reconstruct the flat
     * {@code rateplanassignmenttuple} list the resolver is built from (config-manager does not serve the flat
     * {@code mediationContext.ratePlanAssignmentTuples}). Binding to {@link RateAssignDto} (not the engine
     * {@code rateassign}) also avoids a deserialization crash: config-manager sends {@code endPreviousRate:false}
     * (Boolean), which the engine {@code rateassign.EndPreviousRate} ({@code Byte}) cannot accept.
     */
    public List<RateAssignDto> RateAssignsCustomer;
    public List<RateAssignDto> RateAssignsSupplier;
    public Map<Long, List<PackageAccount>> PartnerIdWisePackageAccounts;

    /**
     * The rating-side config folded in. May be absent until config-manager serves it
     * (open item: the shared EnumServiceCategory namespace) — then MediationContext stays Empty.
     */
    public MediationContextDto MediationContext;
}
