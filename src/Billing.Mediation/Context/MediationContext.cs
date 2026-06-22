using Billing.Mediation.Rating;
using Billing.Mediation.Validation;
using MediationModel;
using TelcobrightMediation;

namespace Billing.Mediation.Context;

/// <summary>
/// The rating-side config for one tenant. It is folded INSIDE that tenant's
/// <c>DynamicContext</c> (see Billing.Config) so there is exactly one config snapshot per
/// tenant: <c>tenant.Context.MediationContext</c>. Immutable; swapped wholesale on reload.
///
/// Holds the category lookup (the cross-system id namespace), the ordered service-group detection
/// rules, and the <see cref="RatePlanResolver"/> over this tenant's verbatim legacy
/// <c>rateplanassignmenttuple</c>s (each carrying its <c>rateassigns</c>). config-manager serves
/// <see cref="Categories"/> from the existing EnumServiceCategory table; <see cref="ServiceGroupRules"/>
/// arrives once its (additive) table is ratified — empty until then.
/// </summary>
public sealed class MediationContext
{
    public IReadOnlyDictionary<int, ServiceCategory> Categories { get; init; }
        = new Dictionary<int, ServiceCategory>();

    public IReadOnlyList<ServiceGroupRule> ServiceGroupRules { get; init; } = [];

    /// <summary>Per-service-group rating configuration (the legacy <c>ServiceGroupConfigurations</c>): the
    /// ordered <see cref="RatingRule"/>s the rater runs for a detected SG. Defaults to the built-in set
    /// (<see cref="ServiceGroupConfiguration.Defaults"/>) until config-manager serves them.</summary>
    public IReadOnlyDictionary<int, ServiceGroupConfiguration> ServiceGroupConfigurations { get; init; }
        = ServiceGroupConfiguration.Defaults;

    /// <summary>The COMMON post-mediation qualification checklist run for every cdr regardless of service
    /// group (legacy CommonMediationCheckListValidator), before the per-SG answered/unanswered checklists.</summary>
    public IReadOnlyList<IValidationRule<cdr>> CommonChecklist { get; init; } = [];

    /// <summary>Resolves which rate-plan-assignment tuples apply to a call (by service group + direction +
    /// partner/route), built from this tenant's legacy <c>rateplanassignmenttuple</c>s. The resolved tuples
    /// then become <c>TupleByPeriod</c> keys into the <see cref="RateCache"/>.</summary>
    public RatePlanResolver RatePlanResolver { get; init; } = RatePlanResolver.Empty;

    /// <summary>The legacy per-day rate cache (<c>DateRangeWiseRateDic</c>), populated lazily for each day a
    /// call touches via <see cref="TupleRateLoader"/> over this tenant's config-served tuples. The legacy
    /// <c>PrefixMatcher</c> longest-prefixes over it. Built together with <see cref="RatePlanResolver"/> from
    /// the SAME tuples (use <see cref="ForRating"/>); empty by default.</summary>
    public RateCache RateCache { get; init; } = new(new TupleRateLoader([]));

    /// <summary>Builds a rating context from one tenant's legacy <c>rateplanassignmenttuple</c>s (each
    /// carrying its <c>rateassigns</c>): the <see cref="RatePlanResolver"/> (which tuples apply) and the
    /// <see cref="RateCache"/> (their rates, per day) are derived from the SAME list so they can never drift.</summary>
    public static MediationContext ForRating(
        IReadOnlyList<rateplanassignmenttuple> tuples,
        IReadOnlyDictionary<int, ServiceCategory>? categories = null,
        IReadOnlyList<ServiceGroupRule>? serviceGroupRules = null,
        IReadOnlyDictionary<int, ServiceGroupConfiguration>? serviceGroupConfigurations = null) => new()
    {
        Categories = categories ?? new Dictionary<int, ServiceCategory>(),
        ServiceGroupRules = serviceGroupRules ?? [],
        ServiceGroupConfigurations = serviceGroupConfigurations ?? ServiceGroupConfiguration.Defaults,
        RatePlanResolver = RatePlanResolver.Build(tuples),
        RateCache = new RateCache(new TupleRateLoader(tuples)),
    };

    /// <summary>An empty context — the safe default before the first successful load.</summary>
    public static MediationContext Empty { get; } = new();
}
