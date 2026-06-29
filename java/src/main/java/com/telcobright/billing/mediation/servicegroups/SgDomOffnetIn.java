package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;

import java.util.Map;

/**
 * SG 11 — "Domestic Incoming Calls [iptsp/pbx]". Claims a call whose InPartner is an ICX partner
 * ({@code PartnerType == 2}) and normalizes the ORIGINATING (calling) number for rating.
 * Lean port of the detection half of legacy {@code TelcobrightMediation.SgDomOffnetIn.Execute}.
 */
public final class SgDomOffnetIn implements IServiceGroupDetector {
    /** ICX partner — the InPartner type that makes a call domestic-incoming. */
    public static final int IcxPartnerType = 2;

    @Override public int Id() { return 11; }
    @Override public String RuleName() { return "Domestic Incoming Calls [iptsp/pbx]"; }

    @Override
    public ServiceGroupMatch Detect(cdr cdr, Map<Integer, Partner> partners) {
        if (cdr.InPartnerId == null) return null;
        var inPartner = partners.get(cdr.InPartnerId);
        if (inPartner == null) return null;
        if (inPartner.PartnerType() == null || inPartner.PartnerType() != IcxPartnerType) return null;

        cdr.ServiceGroup = Id();
        var normalized = BdNumberNormalizer.Normalize(cdr.OriginatingCallingNumber);
        return new ServiceGroupMatch(Id(), RuleName(), normalized);
    }
}
