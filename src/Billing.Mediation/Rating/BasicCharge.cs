using Billing.Mediation.Context;
using Billing.Mediation.Model;
using Billing.Mediation.ServiceFamilies;
using Billing.Mediation.ServiceGroups;
using MediationModel;

namespace Billing.Mediation.Rating;

/// <summary>
/// The per-leg customer charge end to end: detect the service group → resolve the rate-plan tuples by
/// (idService, direction, partner/route) → <see cref="PrefixMatcher"/> longest-prefixes over the tuples'
/// rateassigns → the SG's <see cref="IServiceFamily"/> computes the charge + tax and returns the
/// <see cref="acc_chargeable"/> (SG10→SfA2ZWithVatTax, SG11→SfDomOffNetInAns).
///
/// One leg only. Route-scoped resolution is deferred (passes the partner scope). The accounting/posting
/// half of the legacy families (GL account, billing rule, acc_transaction) is deferred to the mem-ledger
/// slice — the chargeable here carries the rating fields the summary reads.
/// </summary>
public sealed class BasicCharge
{
    // The family for each (service group, direction). SG10 has a customer leg (SF10 A2Z+VAT) and a
    // supplier leg (SF1 base A2Z); SG11 is customer-only (reseller/customer-only spec).
    private static readonly IReadOnlyDictionary<(int ServiceGroup, AssignmentDirection Direction), IServiceFamily>
        FamilyBySgAndDirection = new Dictionary<(int, AssignmentDirection), IServiceFamily>
        {
            [(10, AssignmentDirection.Customer)] = new SfA2ZWithVatTax(),
            [(10, AssignmentDirection.Supplier)] = new SfA2Z(),
            [(11, AssignmentDirection.Customer)] = new SfDomOffNetInAns(),
        };

    private readonly ServiceGroupDetection _detection;

    public BasicCharge(ServiceGroupDetection detection) => _detection = detection;

    /// <summary>The SG10+SG11 detection pair — the ready instance for tests and the rating flow.</summary>
    public static BasicCharge Default() => new(ServiceGroupDetection.Default());

    public acc_chargeable? Compute(
        cdr cdr, AssignmentDirection direction, MediationContext mediation,
        IReadOnlyDictionary<int, Partner> partners, int maxDecimalPrecision = 8)
    {
        var match = _detection.Detect(cdr, partners);
        if (match is null) return null;
        if (!FamilyBySgAndDirection.TryGetValue((match.Value.ServiceGroupId, direction), out var family)) return null;

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

        return family.Charge(rate, cdr, match.Value.ServiceGroupId, direction, maxDecimalPrecision);
    }
}
