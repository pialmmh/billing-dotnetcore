package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;

import java.util.Map;
import java.util.Set;

/**
 * SG 10 — "Domestic Outgoing Calls [Iptsp/pbx]". Claims a call whose InPartner is a CUSTOMER-side partner and
 * normalizes the TERMINATING (called) number for rating. Lean port of the detection half of legacy
 * {@code TelcobrightMediation.SgDomOffnetOut.Execute}.
 *
 * <p><b>Which partner types count as customer-side, and why it is not just 3.</b> Legacy checked only
 * {@code PartnerType == 3} because it was SINGLE-TIER and, in its estate, the InPartner was always an IOS
 * partner. This service rates the whole ancestor chain and this tenant bills several customer classes, so the
 * detector must claim every type that the tenant actually configures with a CUSTOMER-direction rate assignment.
 * On ccl98 that is, from {@code rateplanassignmenttuple} joined to {@code partner} where
 * {@code AssignDirection = 1} and {@code idService = 10}:
 * <ul>
 *   <li>{@code 3 IOS}      — 22 partners (e.g. 222 "Akij Insaf Ltd.")</li>
 *   <li>{@code 4 RESELLER} — 1 partner (261 "Demo Reseller"): the ROOT tier's customer on a reseller call</li>
 *   <li>{@code 5 CLIENT}   — 7 partners (e.g. 202 "NAC No Chinta Limited", live on the routesphere feed)</li>
 *   <li>{@code 6 PBX}      — 2 partners (252, 259)</li>
 * </ul>
 * The remaining types are carrier/interconnect side and are deliberately NOT claimed here: {@code 2 ANS} belongs
 * to SG11 (domestic incoming), and {@code 1 ICX} (45 carrier partners) has no customer-direction assignment at
 * all. {@code 7 HCC} exists in {@code enumpartnertype} but no partner uses it, so it is left out until it does.
 *
 * <p>This is an explicit ALLOW-list on purpose. An unknown/new partner type therefore fails CLOSED — the call
 * comes back "service group not detected", which is a visible error — rather than being silently swept into
 * SG10 and billed against the wrong rate plan. Widen this set deliberately, from the tenant's tuples.
 *
 * <p>The service group is a property of the CALL (outgoing vs incoming), not of who is billed for it, so every
 * one of these customer classes rolls up into {@code sum_voice_day_03}/{@code hr_03} exactly as before.
 * Mutual exclusivity with SG11 holds: SG11 claims only {@code PartnerType == 2}, which is not in this set.
 */
public final class SgDomOffnetOut implements IServiceGroupDetector {
    /** Retail/foreign (IOS) partner — the original legacy check. */
    public static final int RetailPartnerType = 3;
    /** Reseller — the parent tier's customer on a multi-level (reseller-hierarchy) call. */
    public static final int ResellerPartnerType = 4;
    /** Direct client. */
    public static final int ClientPartnerType = 5;
    /** Hosted PBX customer. */
    public static final int PbxPartnerType = 6;

    /** Every InPartner type this service group bills. See the class javadoc before changing. */
    public static final Set<Integer> CustomerPartnerTypes =
            Set.of(RetailPartnerType, ResellerPartnerType, ClientPartnerType, PbxPartnerType);

    @Override public int Id() { return 10; }
    @Override public String RuleName() { return "Domestic Outgoing Calls [Iptsp/pbx]"; }

    @Override
    public ServiceGroupMatch Detect(cdr cdr, Map<Integer, Partner> partners) {
        if (cdr.InPartnerId == null) return null;
        var inPartner = partners.get(cdr.InPartnerId);
        if (inPartner == null) return null;
        Integer type = inPartner.PartnerType();
        if (type == null || !CustomerPartnerTypes.contains(type)) return null;

        cdr.ServiceGroup = Id();
        var normalized = BdNumberNormalizer.Normalize(cdr.TerminatingCalledNumber);
        return new ServiceGroupMatch(Id(), RuleName(), normalized);
    }
}
