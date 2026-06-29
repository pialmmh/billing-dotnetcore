package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;

import java.util.Map;

/**
 * SG 10 — "Domestic Outgoing Calls [Iptsp/pbx]". Claims a call whose InPartner is a RETAIL partner
 * ({@code PartnerType == 3}) and normalizes the TERMINATING (called) number for rating.
 * Lean port of the detection half of legacy {@code TelcobrightMediation.SgDomOffnetOut.Execute}.
 */
public final class SgDomOffnetOut implements IServiceGroupDetector {
    /** Retail/foreign partner — the InPartner type that makes a call domestic-outgoing. */
    public static final int RetailPartnerType = 3;

    @Override public int Id() { return 10; }
    @Override public String RuleName() { return "Domestic Outgoing Calls [Iptsp/pbx]"; }

    @Override
    public ServiceGroupMatch Detect(cdr cdr, Map<Integer, Partner> partners) {
        if (cdr.InPartnerId == null) return null;
        var inPartner = partners.get(cdr.InPartnerId);
        if (inPartner == null) return null;
        if (inPartner.PartnerType() == null || inPartner.PartnerType() != RetailPartnerType) return null;

        cdr.ServiceGroup = Id();
        var normalized = BdNumberNormalizer.Normalize(cdr.TerminatingCalledNumber);
        return new ServiceGroupMatch(Id(), RuleName(), normalized);
    }
}
