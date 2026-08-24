package com.telcobright.billing.mediation.servicegroups;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.Partner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * SG10 bills every CUSTOMER-side partner type, not just IOS. Derived from the tenant's own config on ccl98
     * (verified identical on master .110): rateplanassignmenttuple joined to partner, idService=10 and
     * AssignDirection=1 (customer) yields exactly types 3 IOS (22 partners), 4 RESELLER (1), 5 CLIENT (7),
     * 6 PBX (2). Type 4 is the ROOT tier's customer on a multi-level reseller call.
     */
    @ParameterizedTest(name = "PartnerType {0} is billed by SG10")
    @ValueSource(ints = {3, 4, 5, 6})
    void Sg10_claims_every_customer_side_partner_type(int partnerType) {
        cdr thisCdr = new cdr();
        thisCdr.InPartnerId = 900;
        thisCdr.TerminatingCalledNumber = "8801912020024";
        thisCdr.OriginatingCallingNumber = "ignored";

        var match = ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner(900, null, partnerType)));

        assertNotNull(match, "PartnerType " + partnerType + " has customer-direction SG10 tuples and must be billed");
        assertEquals(10, match.ServiceGroupId());
        assertEquals(10, thisCdr.ServiceGroup);
        assertEquals("1912020024", match.NormalizedNumber());
    }

    /**
     * Carrier/interconnect and unused types are NOT swept into SG10 — the allow-list fails CLOSED so an
     * unrecognised type surfaces as "service group not detected" instead of being billed on the wrong plan.
     * 1 = ICX (45 carrier partners, no customer-direction assignment), 7 = HCC (no partner uses it).
     */
    @ParameterizedTest(name = "PartnerType {0} is NOT billed by SG10")
    @ValueSource(ints = {1, 7})
    void Sg10_rejects_carrier_and_unused_partner_types(int partnerType) {
        cdr thisCdr = new cdr();
        thisCdr.InPartnerId = 901;
        thisCdr.TerminatingCalledNumber = "8801912020024";

        assertNull(ServiceGroupDetection.Default().Detect(thisCdr, Partners(new Partner(901, null, partnerType))));
        assertEquals(0, thisCdr.ServiceGroup);
    }

    /** SG10 and SG11 stay mutually exclusive: ANS(2) is SG11's alone and is not in SG10's customer set. */
    @Test
    void Sg10_and_Sg11_partner_type_sets_do_not_overlap() {
        assertFalse(SgDomOffnetOut.CustomerPartnerTypes.contains(SgDomOffnetIn.IcxPartnerType),
                "ANS(2) must belong to SG11 only");
        assertEquals(java.util.Set.of(3, 4, 5, 6), SgDomOffnetOut.CustomerPartnerTypes);
    }
}
