using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Mediation.ServiceGroups;

/// <summary>
/// Runs the registered service-group detectors in <c>Id</c> order and returns the first that claims the
/// call — the lean equivalent of legacy <c>CdrProcessor.ExecuteServiceGroups</c> (unset ServiceGroup,
/// run each enabled SG, the first that sets <c>ServiceGroup &gt; 0</c> wins).
///
/// Per-tenant SG enablement (the legacy filtered by which idService has a ServiceGroupConfiguration;
/// for us, by which idService appears in the tenant's rate-plan-assignment tuples) is a future filter —
/// SG10 and SG11 are mutually exclusive by partnerType, so registering both is already safe.
/// </summary>
public sealed class ServiceGroupDetection
{
    private readonly IReadOnlyList<IServiceGroupDetector> _detectors;

    public ServiceGroupDetection(IEnumerable<IServiceGroupDetector> detectors)
        => _detectors = detectors.OrderBy(d => d.Id).ToList();

    /// <summary>The SG10 + SG11 detection pair — the ready instance for tests and the rating flow.</summary>
    public static ServiceGroupDetection Default()
        => new(new IServiceGroupDetector[] { new SgDomOffnetOut(), new SgDomOffnetIn() });

    public ServiceGroupMatch? Detect(cdr cdr, IReadOnlyDictionary<int, Partner> partners)
    {
        cdr.ServiceGroup = 0;   // unset first, as the legacy loop did before each Execute
        foreach (var detector in _detectors)
        {
            var match = detector.Detect(cdr, partners);
            if (match is not null) return match;
        }
        return null;
    }
}
