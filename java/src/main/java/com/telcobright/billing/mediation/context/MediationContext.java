// Ported from legacy Mediation/Context/MediationContext.cs.
package com.telcobright.billing.mediation.context;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.engine.models.enumbillingspan;
import com.telcobright.billing.mediation.engine.models.rate;
import com.telcobright.billing.mediation.engine.models.rateplan;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.mediation.rating.RatePlanResolver;
import com.telcobright.billing.mediation.rating.ratecaching.RateCache;
import com.telcobright.billing.mediation.rating.ratecaching.TupleRateLoader;
import com.telcobright.billing.mediation.validation.IValidationRule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rating-side config for one tenant. It is folded INSIDE that tenant's {@code DynamicContext} so there is
 * exactly one config snapshot per tenant: {@code tenant.Context.MediationContext}. Immutable; swapped wholesale
 * on reload.
 *
 * <p>Holds the category lookup, the ordered service-group detection rules, the per-SG rating configuration, the
 * {@link RatePlanResolver} (which tuples apply), and the {@link RateCache} (their rates, per day) — both derived
 * from the SAME tuples + rate rows + rate plans so they can never drift. It also carries the rating knobs the
 * legacy read off the cache/CdrSetting: {@code DicRatePlan} (idrateplan -> rateplan), {@code BillingSpans}
 * (uom -> seconds) and {@code MaxDecimalPrecision}, threaded into the rating path (BasicCharge -> family.Charge
 * -> A2ZRater) so billing span + RateAmountRoundupDecimal can be read.</p>
 */
public final class MediationContext {
    public Map<Integer, ServiceCategory> Categories = new HashMap<>();

    public List<ServiceGroupRule> ServiceGroupRules = List.of();

    /**
     * Per-service-group rating configuration (the legacy {@code ServiceGroupConfigurations}): the ordered
     * {@code RatingRule}s the rater runs for a detected SG. Defaults to the built-in set until config-manager
     * serves them.
     */
    public Map<Integer, ServiceGroupConfiguration> ServiceGroupConfigurations = ServiceGroupConfiguration.Defaults;

    /** The COMMON post-mediation qualification checklist run for every cdr, before the per-SG checklists. */
    public List<IValidationRule<cdr>> CommonChecklist = List.of();

    /** Resolves which rate-plan-assignment tuples apply to a call (legacy GetAssignmentTuples). */
    public RatePlanResolver RatePlanResolver = com.telcobright.billing.mediation.rating.RatePlanResolver.Empty;

    /** The legacy per-day rate cache ({@code DateRangeWiseRateDic}), built from this tenant's tuples + rates. */
    public RateCache RateCache = new RateCache(new TupleRateLoader(List.of(), new HashMap<>(), new HashMap<>()));

    /** legacy RateCache.DicRatePlan — key = idrateplan as string. The rater reads field4/BillingSpan/RateAmountRoundupDecimal. */
    public Map<String, rateplan> DicRatePlan = new HashMap<>();

    /** legacy MediationContext.BillingSpans — uom (rateplan.BillingSpan) -> enumbillingspan (carrying the seconds value). */
    public Map<String, enumbillingspan> BillingSpans = new HashMap<>();

    /** legacy CdrSetting.MaxDecimalPrecision — the HALF_EVEN rounding scale for the final amount (default 8). */
    public int MaxDecimalPrecision = 8;

    /**
     * Builds a rating context from one tenant's legacy {@code rateplanassignmenttuple}s (each carrying its
     * {@code rateassigns} JOIN rows), the rate rows per rate plan, the rate plans, the billing-span uom table and
     * the max precision: the {@link RatePlanResolver} (which tuples apply) and the {@link RateCache} (their rates,
     * per day) are derived from the SAME data so they can never drift.
     */
    public static MediationContext ForRating(
            List<rateplanassignmenttuple> tuples,
            Map<Integer, List<rate>> rateRowsByRatePlan,
            Map<String, rateplan> dicRatePlan,
            Map<String, enumbillingspan> billingSpans,
            int maxDecimalPrecision,
            Map<Integer, ServiceCategory> categories,
            List<ServiceGroupRule> serviceGroupRules,
            Map<Integer, ServiceGroupConfiguration> serviceGroupConfigurations,
            List<IValidationRule<cdr>> commonChecklist) {
        MediationContext ctx = new MediationContext();
        ctx.Categories = categories != null ? categories : new HashMap<>();
        ctx.ServiceGroupRules = serviceGroupRules != null ? serviceGroupRules : List.of();
        ctx.ServiceGroupConfigurations = serviceGroupConfigurations != null
                ? serviceGroupConfigurations : ServiceGroupConfiguration.Defaults;
        ctx.CommonChecklist = commonChecklist != null ? commonChecklist : List.of();
        ctx.DicRatePlan = dicRatePlan != null ? dicRatePlan : new HashMap<>();
        ctx.BillingSpans = billingSpans != null ? billingSpans : new HashMap<>();
        ctx.MaxDecimalPrecision = maxDecimalPrecision;
        // FQN: the simple name `RatePlanResolver` would resolve to the instance field, not the type.
        ctx.RatePlanResolver = com.telcobright.billing.mediation.rating.RatePlanResolver.Build(tuples != null ? tuples : List.of());
        ctx.RateCache = new RateCache(
                new TupleRateLoader(tuples, rateRowsByRatePlan, ctx.DicRatePlan), ctx.DicRatePlan);
        return ctx;
    }

    /** Convenience overload: rating tables only, the rest default. */
    public static MediationContext ForRating(
            List<rateplanassignmenttuple> tuples,
            Map<Integer, List<rate>> rateRowsByRatePlan,
            Map<String, rateplan> dicRatePlan,
            Map<String, enumbillingspan> billingSpans,
            int maxDecimalPrecision) {
        return ForRating(tuples, rateRowsByRatePlan, dicRatePlan, billingSpans, maxDecimalPrecision,
                null, null, null, null);
    }

    /** An empty context — the safe default before the first successful load. */
    public static final MediationContext Empty = new MediationContext();
}
