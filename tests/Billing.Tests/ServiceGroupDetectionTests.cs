using Billing.Mediation.Model;
using Billing.Mediation.ServiceGroups;
using MediationModel;

namespace Billing.Tests;

/// <summary>SG 10/11 detection: claim by InPartner.PartnerType (3=retail→SG10 normalizes the terminating
/// number, 2=icx→SG11 normalizes the originating number), and reject everything else. Mirrors the legacy
/// ExecuteServiceGroups "first to claim wins" loop.</summary>
public class ServiceGroupDetectionTests
{
    private static IReadOnlyDictionary<int, Partner> Partners(params Partner[] ps)
        => ps.ToDictionary(p => p.IdPartner);

    [Fact]
    public void Sg10_claims_retail_partner_and_normalizes_terminating()
    {
        var thisCdr = new cdr { InPartnerId = 5, TerminatingCalledNumber = "8801712345678", OriginatingCallingNumber = "ignored" };
        var match = ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner { IdPartner = 5, PartnerType = 3 }));

        Assert.NotNull(match);
        Assert.Equal(10, match.Value.ServiceGroupId);
        Assert.Equal(10, thisCdr.ServiceGroup);                 // mutated onto the cdr for the downstream charge path
        Assert.Equal("1712345678", match.Value.NormalizedNumber);
    }

    [Fact]
    public void Sg11_claims_icx_partner_and_normalizes_originating()
    {
        var thisCdr = new cdr { InPartnerId = 7, OriginatingCallingNumber = "008801812345678", TerminatingCalledNumber = "ignored" };
        var match = ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner { IdPartner = 7, PartnerType = 2 }));

        Assert.NotNull(match);
        Assert.Equal(11, match.Value.ServiceGroupId);
        Assert.Equal(11, thisCdr.ServiceGroup);
        Assert.Equal("1812345678", match.Value.NormalizedNumber);
    }

    [Fact]
    public void No_claim_for_other_partner_type()
    {
        var thisCdr = new cdr { InPartnerId = 9, TerminatingCalledNumber = "8801712345678" };
        var match = ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner { IdPartner = 9, PartnerType = 1 }));

        Assert.Null(match);
        Assert.Equal(0, thisCdr.ServiceGroup);
    }

    [Fact]
    public void No_claim_when_partner_unknown_or_inpartnerid_null()
    {
        var det = ServiceGroupDetection.Default();
        Assert.Null(det.Detect(new cdr { InPartnerId = 404, TerminatingCalledNumber = "880171" }, Partners()));
        Assert.Null(det.Detect(new cdr { InPartnerId = null, TerminatingCalledNumber = "880171" }, Partners()));
    }
}
