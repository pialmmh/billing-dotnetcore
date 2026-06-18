using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Mediation.ServiceGroups;

/// <summary>The detected service group for a call plus the normalized chargeable number that the rate
/// lookup (longest-prefix over the resolved rate-plan tuples' rateassigns) consumes.</summary>
public readonly record struct ServiceGroupMatch(int ServiceGroupId, string RuleName, string NormalizedNumber);

/// <summary>
/// The lean port of a legacy <c>IServiceGroup</c>'s DETECTION role. It inspects a cdr against the
/// tenant's partners and, if it claims the call, sets <c>cdr.ServiceGroup</c> to its <see cref="Id"/>,
/// normalizes the chargeable number, and returns the <see cref="ServiceGroupMatch"/>; otherwise it
/// returns <c>null</c> and leaves the cdr untouched. One detector per service group (SG10 outgoing,
/// SG11 incoming).
///
/// The legacy <c>Execute</c> also ran <c>AnsPrefixFinder</c> to stamp country/destination ids onto the
/// cdr — that feeds the summary tuple, not the basic charge, so it is deferred to the summary slice.
/// </summary>
public interface IServiceGroupDetector
{
    int Id { get; }
    string RuleName { get; }
    ServiceGroupMatch? Detect(cdr cdr, IReadOnlyDictionary<int, Partner> partners);
}
