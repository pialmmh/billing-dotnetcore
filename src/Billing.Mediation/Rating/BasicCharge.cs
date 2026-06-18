using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.ServiceGroups;
using MediationModel;

namespace Billing.Mediation.Rating;

/// <summary>The basic per-leg charge for one call: the detected service group, the chosen rate plan and
/// matched prefix, and the pulse/surcharge-adjusted duration + amount.</summary>
public readonly record struct BasicChargeResult(
    int ServiceGroupId, int IdRatePlan, string MatchedPrefix, decimal BilledDurationSec, decimal Amount);

/// <summary>
/// Wires the basic charge end to end for a single direction (the architect's "basic charge first" slice):
/// detect the service group → resolve the rate-plan tuples by (idService, direction, partner/route) →
/// <see cref="PrefixMatcher"/> longest-prefixes the normalized number over the tuples' rateassigns →
/// <see cref="A2ZRater"/> runs the duration+amount math.
///
/// One leg only (customer or supplier). Route-scoped resolution is deferred (route id needs the
/// switch/route map we do not load yet), so this passes the partner scope; <c>billingSpanSec</c> defaults
/// to 60 (per-minute) until the rate plan supplies it.
/// </summary>
public sealed class BasicCharge
{
    private readonly ServiceGroupDetection _detection;

    public BasicCharge(ServiceGroupDetection detection) => _detection = detection;

    /// <summary>The SG10+SG11 detection pair — the ready instance for tests and the rating flow.</summary>
    public static BasicCharge Default() => new(ServiceGroupDetection.Default());

    public BasicChargeResult? Compute(
        cdr cdr, AssignmentDirection direction, MediationContext mediation,
        IReadOnlyDictionary<int, Partner> partners, int billingSpanSec = 60)
    {
        var match = _detection.Detect(cdr, partners);
        if (match is null) return null;

        // Customer leg keys off the in-partner, supplier leg off the out-partner (legacy A2ZRater).
        var idPartner = direction == AssignmentDirection.Supplier ? cdr.OutPartnerId : cdr.InPartnerId;

        var tuples = mediation.RatePlanResolver.Resolve(
            match.Value.ServiceGroupId, (int)direction, idPartner, route: null);
        if (tuples.Count == 0) return null;

        var category = cdr.Category ?? 1;          // legacy defaults: 1 = call
        var subCategory = cdr.SubCategory ?? 1;     //                  1 = voice
        var answerTime = cdr.AnswerTime ?? cdr.StartTime;

        var rate = new PrefixMatcher(tuples, category, subCategory, answerTime)
            .MatchPrefix(match.Value.NormalizedNumber);
        if (rate is null) return null;

        var charge = A2ZRater.Rate(rate, cdr.DurationSec, billingSpanSec);
        return new BasicChargeResult(
            match.Value.ServiceGroupId, (int)(rate.idrateplan ?? 0), rate.Prefix.ToString(),
            charge.BilledDurationSec, charge.Amount);
    }
}
