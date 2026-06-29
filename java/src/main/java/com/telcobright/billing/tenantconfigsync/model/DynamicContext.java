package com.telcobright.billing.tenantconfigsync.model;

import com.telcobright.billing.mediation.context.MediationContext;
import com.telcobright.billing.mediation.model.PackageAccount;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.model.Rate;
import com.telcobright.billing.mediation.model.RatePlan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One tenant's immutable config snapshot — the .NET mirror of routesphere's
 * {@code DynamicContext}. Per the design, the rating brain rides inside it as
 * {@link MediationContext}, so resolving a tenant gives you everything:
 * {@code tenant.Context.MediationContext}.
 *
 * <p>Carries the billing-relevant maps (partners, rate plans, today's rates, rate
 * assignments, package accounts). Other routesphere maps (dialplan/DID/SMS) are omitted —
 * this service rates, it does not route. The whole object is replaced on each reload.
 *
 * <p>Faithful-port note: the C# {@code init}-only properties become public mutable fields
 * (Java cannot express init-only without builders/records, and Tenant/DynamicContext are
 * constructed via field assignment by the mapper). Field names are kept verbatim (PascalCase)
 * so call sites such as {@code tenant.Context.MediationContext} are identical.
 */
public final class DynamicContext {
    /** The rating brain for this tenant (folded in, per design §4a). */
    public MediationContext MediationContext = com.telcobright.billing.mediation.context.MediationContext.Empty;

    public Map<Integer, Partner> Partners = new HashMap<>();

    public Map<Integer, RatePlan> RatePlans = new HashMap<>();

    /** plan id → (prefix/zone → rate). Mirrors routesphere ratePlanWiseTodaysRates. */
    public Map<Integer, Map<String, Rate>> RatePlanWiseTodaysRates = new HashMap<>();

    public List<rateassign> RateAssignsCustomer = List.of();

    public List<rateassign> RateAssignsSupplier = List.of();

    /** partner id → package accounts, ranked (ACTIVE, by onSelectPriority). Eligibility only. */
    public Map<Long, List<PackageAccount>> PartnerIdWisePackageAccounts = new HashMap<>();

    /** The safe default before a tenant's first successful load. */
    public static final DynamicContext Empty = new DynamicContext();
}
