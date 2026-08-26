package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;

import java.util.Map;

/**
 * SG 15 — "International Outgoing Calls [Iptsp]" (legacy {@code SgIntlOutIptsp}). An international-outgoing call is
 * identified by the DESTINATION ({@code OriginatingCalledNumber}, the dialed number) carrying the {@code 00}
 * international prefix — e.g. {@code 0097180044444} (UAE), {@code 006621465999} (Thailand), and also
 * {@code 00880…} (a BD number dialed in international format). This is the deterministic, no-miss discriminator:
 * a {@code 00…} call is claimed by SG15 and can never fall into SG10/SG11.
 *
 * <p><b>Ordering (critical):</b> {@link #DetectionPriority()} returns a value LOWER than SG10/SG11 so SG15 is
 * evaluated FIRST. SG10 ({@code SgDomOffnetOut}) claims any InPartner of type 3/4/5/6 with NO destination check,
 * so an international call from a type-3 IPTSP would otherwise be stolen by SG10 and billed as domestic. Because
 * SG15 only claims {@code 00…} destinations (disjoint from the domestic {@code 09xxx}/{@code 8801xxx} that SG10/SG11
 * bill), running it first cannot disturb existing SG10/SG11 classification — a non-{@code 00} call falls straight
 * through to them.
 *
 * <p>The returned {@link ServiceGroupMatch} carries the {@code 00}-stripped destination, which the SG15 Xyz rate
 * pass longest-prefixes over the one common international plan (idService=7, "Outgoing XYZ @IGW New Rate").
 * Classification is independent of {@code ChargingStatus}: a failed/0-duration {@code 00} call is still SG15
 * (legacy parity) — the zero-charge decision is made later in the Xyz family, not here.
 */
public final class SgIntlOutIptsp implements IServiceGroupDetector {
    /** The international access prefix that marks an outgoing destination as SG15. */
    public static final String IntlPrefix = "00";

    @Override public int Id() { return 15; }
    @Override public String RuleName() { return "International Outgoing Calls [Iptsp]"; }

    /** Evaluated before SG10 (10) / SG11 (11): a {@code 00…} call must be SG15, never captured by SG10's type check. */
    @Override public int DetectionPriority() { return 1; }

    @Override
    public ServiceGroupMatch Detect(cdr cdr, Map<Integer, Partner> partners) {
        String dest = cdr.OriginatingCalledNumber;
        if (dest == null || dest.isEmpty()) return null;
        String d = dest.startsWith("+") ? dest.substring(1) : dest;
        if (!d.startsWith(IntlPrefix)) return null;          // not international -> let SG10/SG11 claim it
        cdr.ServiceGroup = Id();
        // strip the leading 00 for the international rate lookup (plan 117 prefixes are 971 / 66 / 9718 …).
        String stripped = d.substring(IntlPrefix.length());
        return new ServiceGroupMatch(Id(), RuleName(), stripped);
    }
}
