using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Mediation.ServiceGroups;

/// <summary>
/// SG 10 — "Domestic Outgoing Calls [Iptsp/pbx]". Claims a call whose InPartner is a RETAIL partner
/// (<c>PartnerType == 3</c>) and normalizes the TERMINATING (called) number for rating.
/// Lean port of the detection half of legacy <c>TelcobrightMediation.SgDomOffnetOut.Execute</c>.
/// </summary>
public sealed class SgDomOffnetOut : IServiceGroupDetector
{
    /// <summary>Retail/foreign partner — the InPartner type that makes a call domestic-outgoing.</summary>
    public const int RetailPartnerType = 3;

    public int Id => 10;
    public string RuleName => "Domestic Outgoing Calls [Iptsp/pbx]";

    public ServiceGroupMatch? Detect(cdr cdr, IReadOnlyDictionary<int, Partner> partners)
    {
        if (cdr.InPartnerId is null) return null;
        if (!partners.TryGetValue(cdr.InPartnerId.Value, out var inPartner)) return null;
        if (inPartner.PartnerType != RetailPartnerType) return null;

        cdr.ServiceGroup = Id;
        var normalized = BdNumberNormalizer.Normalize(cdr.TerminatingCalledNumber);
        return new ServiceGroupMatch(Id, RuleName, normalized);
    }
}
