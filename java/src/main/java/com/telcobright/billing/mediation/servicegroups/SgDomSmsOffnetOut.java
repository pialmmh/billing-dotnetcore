package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.sms.SmsOutgoing;

import java.util.Map;

/**
 * SG 20 — "Domestic Outgoing SMS" (legacy {@code SgDomSmsOffnetOut}). FIXED, not detected: it is registered ONLY
 * in {@link ServiceGroupDetection#SmsOutgoing()}, and every cdr that enters through the outgoing-SMS intake is
 * SG20 by construction. It deliberately ignores the partner type (legacy keyed on PartnerType 3, which the
 * voice SG10 detector also claims) and the event's own {@code serviceGroup} field.
 *
 * <p>The match carries the RAW called number; the SMS composite matcher reads the calling and called numbers
 * straight off the cdr, without normalisation, as routesphere does.</p>
 */
public final class SgDomSmsOffnetOut implements IServiceGroupDetector {
    @Override public int Id() { return SmsOutgoing.ServiceGroupId; }

    @Override public String RuleName() { return SmsOutgoing.ServiceGroupName; }

    @Override
    public ServiceGroupMatch Detect(cdr cdr, Map<Integer, Partner> partners) {
        return new ServiceGroupMatch(SmsOutgoing.ServiceGroupId, SmsOutgoing.ServiceGroupName, cdr.OriginatingCalledNumber);
    }
}
