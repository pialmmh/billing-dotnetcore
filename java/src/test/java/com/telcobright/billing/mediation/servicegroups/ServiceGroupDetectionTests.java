package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SG 10/11 detection: claim by InPartner.PartnerType (3=retail-&gt;SG10 normalizes the terminating number,
 * 2=icx-&gt;SG11 normalizes the originating number), and reject everything else. Mirrors the legacy
 * ExecuteServiceGroups "first to claim wins" loop. Faithful port of ServiceGroupDetectionTests.cs.
 *
 * <p>The C# {@code ServiceGroupMatch?} (nullable struct) becomes a nullable {@link ServiceGroupMatch} record
 * reference: {@code match.Value.ServiceGroupId} -&gt; {@code match.ServiceGroupId()}.</p>
 */
class ServiceGroupDetectionTests {

    private static Map<Integer, Partner> Partners(Partner... ps) {
        Map<Integer, Partner> m = new HashMap<>();
        for (Partner p : ps) m.put(p.IdPartner(), p);
        return m;
    }

    @Test
    void Sg10_claims_retail_partner_and_normalizes_terminating() {
        cdr thisCdr = new cdr();
        thisCdr.InPartnerId = 5;
        thisCdr.TerminatingCalledNumber = "8801712345678";
        thisCdr.OriginatingCallingNumber = "ignored";

        var match = ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner(5, null, 3)));

        assertNotNull(match);
        assertEquals(10, match.ServiceGroupId());
        assertEquals(10, thisCdr.ServiceGroup);                 // mutated onto the cdr for the downstream charge path
        assertEquals("1712345678", match.NormalizedNumber());
    }

    @Test
    void Sg11_claims_icx_partner_and_normalizes_originating() {
        cdr thisCdr = new cdr();
        thisCdr.InPartnerId = 7;
        thisCdr.OriginatingCallingNumber = "008801812345678";
        thisCdr.TerminatingCalledNumber = "ignored";

        var match = ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner(7, null, 2)));

        assertNotNull(match);
        assertEquals(11, match.ServiceGroupId());
        assertEquals(11, thisCdr.ServiceGroup);
        assertEquals("1812345678", match.NormalizedNumber());
    }

    @Test
    void No_claim_for_other_partner_type() {
        cdr thisCdr = new cdr();
        thisCdr.InPartnerId = 9;
        thisCdr.TerminatingCalledNumber = "8801712345678";

        var match = ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner(9, null, 1)));

        assertNull(match);
        assertEquals(0, thisCdr.ServiceGroup);
    }

    @Test
    void No_claim_when_partner_unknown_or_inpartnerid_null() {
        var det = ServiceGroupDetection.Default();

        cdr unknown = new cdr();
        unknown.InPartnerId = 404;
        unknown.TerminatingCalledNumber = "880171";
        assertNull(det.Detect(unknown, Partners()));

        cdr nullPartner = new cdr();
        nullPartner.InPartnerId = null;
        nullPartner.TerminatingCalledNumber = "880171";
        assertNull(det.Detect(nullPartner, Partners()));
    }
}
