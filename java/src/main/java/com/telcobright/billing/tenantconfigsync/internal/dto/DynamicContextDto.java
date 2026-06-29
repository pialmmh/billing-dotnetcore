package com.telcobright.billing.tenantconfigsync.internal.dto;

import com.telcobright.billing.mediation.model.PackageAccount;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.mediation.model.RateAssign;
import com.telcobright.billing.mediation.model.RatePlan;

import java.util.List;
import java.util.Map;

public final class DynamicContextDto {
    public Map<Integer, Partner> Partners;
    public Map<Integer, RatePlan> RatePlans;
    public Map<Integer, Map<String, Rate>> RatePlanWiseTodaysRates;
    public List<RateAssign> RateAssignsCustomer;
    public List<RateAssign> RateAssignsSupplier;
    public Map<Long, List<PackageAccount>> PartnerIdWisePackageAccounts;

    /**
     * The rating-side config folded in. May be absent until config-manager serves it
     * (open item: the shared EnumServiceCategory namespace) — then MediationContext stays Empty.
     */
    public MediationContextDto MediationContext;
}
