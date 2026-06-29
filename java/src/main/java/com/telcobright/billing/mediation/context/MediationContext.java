// Ported from legacy Mediation/Context/MediationContext.cs.
package com.telcobright.billing.mediation.context;

import com.telcobright.billing.mediation.engine.models.cdr;
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
 * <p>Holds the category lookup (the cross-system id namespace), the ordered service-group detection rules, and
 * the {@link RatePlanResolver} over this tenant's verbatim legacy {@code rateplanassignmenttuple}s (each
 * carrying its {@code rateassigns}). config-manager serves {@code Categories} from the existing
 * EnumServiceCategory table; {@code ServiceGroupRules} arrives once its (additive) table is ratified — empty
 * until then.</p>
 *
 * <p>FAITHFUL-PORT NOTE: the C# {@code sealed class} with {@code { get; init; }} defaults + static factories
 * is ported to a final class with public fields (defaults matching C#), a static {@code Empty} singleton, and
 * static {@code ForRating(...)}. Call sites ({@code ctx.RatePlanResolver}, {@code MediationContext.Empty},
 * {@code MediationContext.ForRating(...)}) stay identical.</p>
 */
public final class MediationContext {
    public Map<Integer, ServiceCategory> Categories = new HashMap<>();

    public List<ServiceGroupRule> ServiceGroupRules = List.of();

    /**
     * Per-service-group rating configuration (the legacy {@code ServiceGroupConfigurations}): the ordered
     * {@code RatingRule}s the rater runs for a detected SG. Defaults to the built-in set
     * ({@code ServiceGroupConfiguration.Defaults}) until config-manager serves them.
     */
    public Map<Integer, ServiceGroupConfiguration> ServiceGroupConfigurations = ServiceGroupConfiguration.Defaults;

    /**
     * The COMMON post-mediation qualification checklist run for every cdr regardless of service group
     * (legacy CommonMediationCheckListValidator), before the per-SG answered/unanswered checklists.
     */
    public List<IValidationRule<cdr>> CommonChecklist = List.of();

    /**
     * Resolves which rate-plan-assignment tuples apply to a call (by service group + direction +
     * partner/route), built from this tenant's legacy {@code rateplanassignmenttuple}s.
     */
    // NOTE: the field name equals the type name; the right-hand type is fully qualified so the simple name
    // resolves to the type (not to this field) inside this instance initializer.
    public RatePlanResolver RatePlanResolver = com.telcobright.billing.mediation.rating.RatePlanResolver.Empty;

    /**
     * The legacy per-day rate cache ({@code DateRangeWiseRateDic}), populated lazily for each day a call
     * touches via {@link TupleRateLoader} over this tenant's config-served tuples. Built together with
     * {@code RatePlanResolver} from the SAME tuples (use {@link #ForRating}); empty by default.
     */
    public RateCache RateCache = new RateCache(new TupleRateLoader(List.of()));

    /**
     * Builds a rating context from one tenant's legacy {@code rateplanassignmenttuple}s (each carrying its
     * {@code rateassigns}): the {@link RatePlanResolver} (which tuples apply) and the {@link RateCache} (their
     * rates, per day) are derived from the SAME list so they can never drift.
     */
    public static MediationContext ForRating(
            List<rateplanassignmenttuple> tuples,
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
        // FQN: the simple name `RatePlanResolver` would resolve to the instance field, not the type.
        ctx.RatePlanResolver = com.telcobright.billing.mediation.rating.RatePlanResolver.Build(tuples);
        ctx.RateCache = new RateCache(new TupleRateLoader(tuples));
        return ctx;
    }

    /** Convenience overload mirroring the C# optional parameters (tuples required, the rest default to null). */
    public static MediationContext ForRating(List<rateplanassignmenttuple> tuples) {
        return ForRating(tuples, null, null, null, null);
    }

    /** An empty context — the safe default before the first successful load. */
    public static final MediationContext Empty = new MediationContext();
}
