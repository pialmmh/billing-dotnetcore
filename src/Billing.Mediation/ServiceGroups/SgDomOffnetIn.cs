using Billing.Mediation.Model;
using MediationModel;

namespace Billing.Mediation.ServiceGroups;

/// <summary>
/// SG 11 — "Domestic Incoming Calls [iptsp/pbx]". Claims a call whose InPartner is an ICX partner
/// (<c>PartnerType == 2</c>) and normalizes the ORIGINATING (calling) number for rating.
/// Lean port of the detection half of legacy <c>TelcobrightMediation.SgDomOffnetIn.Execute</c>.
/// </summary>
public sealed class SgDomOffnetIn : IServiceGroupDetector
{
    /// <summary>ICX partner — the InPartner type that makes a call domestic-incoming.</summary>
    public const int IcxPartnerType = 2;

    public int Id => 11;
    public string RuleName => "Domestic Incoming Calls [iptsp/pbx]";

    public ServiceGroupMatch? Detect(cdr cdr, IReadOnlyDictionary<int, Partner> partners)
    {
        if (cdr.InPartnerId is null) return null;
        if (!partners.TryGetValue(cdr.InPartnerId.Value, out var inPartner)) return null;
        if (inPartner.PartnerType != IcxPartnerType) return null;

        cdr.ServiceGroup = Id;
        var normalized = BdNumberNormalizer.Normalize(cdr.OriginatingCallingNumber);
        return new ServiceGroupMatch(Id, RuleName, normalized);
    }
}
