using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.ServiceFamilies;
using Billing.Mediation.ServiceGroups;
using LibraryExtensions;
using MediationModel;
using TelcobrightMediation;

namespace Billing.Mediation.Rating;

/// <summary>
/// The per-cdr charge: detect the service group → run that SG's CONFIGURED rating rules
/// (<see cref="ServiceGroupConfiguration.Rules"/>, legacy <c>ExecuteRating</c>). Each rating rule names a
/// service family (by id) and an assignment direction; for each, the rate-plan tuples are resolved by
/// (idService, direction, partner/route), the legacy <see cref="PrefixMatcher"/> longest-prefixes over the
/// per-day <see cref="RateCache"/>, and the rule's <see cref="IServiceFamily"/> computes the charge → an
/// <see cref="acc_chargeable"/>. Replaces the previously-hardcoded (SG,direction)→family map: which families
/// run, in which direction, is now configuration (served by config-manager; built-in defaults otherwise).
///
/// The accounting/posting half of the legacy families (GL account, billing rule, acc_transaction) is
/// deferred to the mem-ledger slice — the chargeable here carries the rating fields the summary reads.
/// </summary>
public sealed class BasicCharge
{
    private readonly ServiceGroupDetection _detection;
    private readonly IReadOnlyDictionary<int, IServiceFamily> _families;

    public BasicCharge(ServiceGroupDetection detection, IEnumerable<IServiceFamily>? families = null)
    {
        _detection = detection;
        _families = (families ?? DefaultFamilies()).ToDictionary(f => f.Id);
    }

    /// <summary>The SG10+SG11 detection pair + the built-in service families — the ready instance.</summary>
    public static BasicCharge Default() => new(ServiceGroupDetection.Default());

    // The legacy MEF service-family container, as a fixed registry: SF1 (base A2Z), SF10 (A2Z+VAT), SF11.
    private static IServiceFamily[] DefaultFamilies() =>
        new IServiceFamily[] { new SfA2Z(), new SfA2ZWithVatTax(), new SfDomOffNetInAns() };

    /// <summary>Run ALL of the detected service group's configured rating rules (legacy ExecuteRating) and
    /// return the resulting chargeables (one per rule that matched a rate). Empty if no SG is detected, the
    /// SG is disabled/unconfigured, or no rule produced a charge.</summary>
    public IReadOnlyList<acc_chargeable> Rate(
        cdr cdr, MediationContext mediation, IReadOnlyDictionary<int, Partner> partners, int maxDecimalPrecision = 8)
    {
        var match = _detection.Detect(cdr, partners);
        if (match is null) return [];
        cdr.ServiceGroup = match.Value.ServiceGroupId;   // stamp the detected SG (legacy serviceGroup.Execute)
        if (!mediation.ServiceGroupConfigurations.TryGetValue(match.Value.ServiceGroupId, out var sgConfig)
            || sgConfig.Disabled) return [];

        var chargeables = new List<acc_chargeable>();
        foreach (var rule in sgConfig.Rules.OfType<RatingRule>())   // the rating-kind rules, in order
        {
            var chargeable = ChargeRule(cdr, mediation, match.Value, rule, maxDecimalPrecision);
            if (chargeable is not null) chargeables.Add(chargeable);
        }
        return chargeables;
    }

    /// <summary>The single chargeable for the detected SG's first configured rule in the given direction —
    /// the per-leg convenience the per-call finalize path uses. Null if not detected / no such rule / no rate.</summary>
    public acc_chargeable? Compute(
        cdr cdr, AssignmentDirection direction, MediationContext mediation,
        IReadOnlyDictionary<int, Partner> partners, int maxDecimalPrecision = 8)
    {
        var match = _detection.Detect(cdr, partners);
        if (match is null) return null;
        if (!mediation.ServiceGroupConfigurations.TryGetValue(match.Value.ServiceGroupId, out var sgConfig)
            || sgConfig.Disabled) return null;

        var rule = sgConfig.Rules.OfType<RatingRule>().FirstOrDefault(r => r.AssignDirection == (int)direction);
        return rule is null ? null : ChargeRule(cdr, mediation, match.Value, rule, maxDecimalPrecision);
    }

    /// <summary>Detect the service group and match the CUSTOMER rate for a call WITHOUT charging it — the
    /// pre-call (max-rate / admission) path. Stamps <c>cdr.ServiceGroup</c>; returns the detected SG id (0 =
    /// not detected) and the matched <see cref="rateassign"/> (null if no SG / no rate).</summary>
    public (int ServiceGroupId, rateassign? Rate) MatchCustomerRate(
        cdr cdr, MediationContext mediation, IReadOnlyDictionary<int, Partner> partners)
    {
        var match = _detection.Detect(cdr, partners);
        if (match is null) return (0, null);
        cdr.ServiceGroup = match.Value.ServiceGroupId;
        var rate = MatchRate(cdr, mediation, match.Value, (int)AssignmentDirection.Customer);
        return (match.Value.ServiceGroupId, rate);
    }

    // One rating rule: resolve the family, look the rate up through the RateCache for the rule's direction,
    // and charge. The legacy A2ZRater path, per rule.
    private acc_chargeable? ChargeRule(
        cdr cdr, MediationContext mediation, ServiceGroupMatch match, RatingRule rule, int maxDecimalPrecision)
    {
        if (!_families.TryGetValue(rule.IdServiceFamily, out var family)) return null;

        var rate = MatchRate(cdr, mediation, match, rule.AssignDirection);
        if (rate is null) return null;

        return family.Charge(rate, cdr, match.ServiceGroupId, (AssignmentDirection)rule.AssignDirection, maxDecimalPrecision);
    }

    // Resolve the rate-plan tuples for the (service group, direction, partner) and longest-prefix the dialed
    // number over the per-day RateCache (legacy PrefixMatcher). Shared by the charge + the max-rate paths.
    private static rateassign? MatchRate(cdr cdr, MediationContext mediation, ServiceGroupMatch match, int assignDirection)
    {
        // Customer leg keys off the in-partner, supplier leg off the out-partner (legacy A2ZRater).
        var idPartner = (AssignmentDirection)assignDirection == AssignmentDirection.Supplier
            ? cdr.OutPartnerId : cdr.InPartnerId;

        var tuples = mediation.RatePlanResolver.Resolve(match.ServiceGroupId, assignDirection, idPartner, route: null);
        if (tuples.Count == 0) return null;

        var category = cdr.Category ?? 1;          // legacy defaults: 1 = call
        var subCategory = cdr.SubCategory ?? 1;     //                  1 = voice
        var answerTime = cdr.AnswerTime ?? cdr.StartTime;

        var day = new DateRange(answerTime.Date, answerTime.Date.AddDays(1));
        var tups = tuples
            .Select(t => new TupleByPeriod { IdAssignmentTuple = t.id, DRange = day, Priority = t.priority })
            .ToList();
        return new PrefixMatcher(mediation.RateCache, match.NormalizedNumber,
            category, subCategory, tups, answerTime).MatchPrefix();
    }
}
