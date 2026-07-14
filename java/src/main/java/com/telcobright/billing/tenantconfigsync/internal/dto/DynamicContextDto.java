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

    /**
     * The served rate-assign rows. config-manager serves each one's rate-plan-assignment tuple NESTED (see
     * {@link RateAssignDto}); the mapper groups them by that tuple id to reconstruct the flat
     * {@code rateplanassignmenttuple} list the RateCache is built from (config-manager does not serve the flat
     * {@code mediationContext.ratePlanAssignmentTuples}).
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
