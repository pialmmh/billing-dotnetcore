package com.telcobright.billing.ingest;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 — the wire as ratified (routesphere {@code docs/architecture/ad-is-a-call.md} §4): the brief's own sample
 * decodes and maps; {@code answerTime} may be null; the four new fields reach {@code cdr.ServiceGroup},
 * {@code IncomingRoute}, {@code OutgoingRoute}, {@code AdditionalMetaData}; and what the wire's schema makes
 * optional is optional here — a view nobody was admitted for is a record, not a dead letter.
 */
class RatifiedWireTests {

    /** The brief's §4 sample, character for character: one view, two tiers, the leaf first. */
    static final String ONE_VIEW_TWO_TIERS = """
            [
              { "tenant": "res_44", "resellerHierarchy": "btcl > res_44", "serviceGroup": 30,
                "sequenceNo": 1790961243000017,
                "callId": "2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa", "channelCallUuid": "2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa",
                "startTime": "2026-10-02 21:14:03", "answerTime": "2026-10-02 21:14:04", "endTime": "2026-10-02 21:14:14",
                "durationSec": 10,
                "originatingCallingNumber": "8801711000001", "terminatingCallingNumber": "8801711000001",
                "originatingCalledNumber": "7001", "terminatingCalledNumber": "7001",
                "callerIp": "10.20.0.1", "receiverIp": "10.10.188.40",
                "hangupCause": "NORMAL_CLEARING", "channelReadCodecName": "image", "pdd": 1.0,
                "incomingRoute": "cola-eid", "outgoingRoute": "dhaka-north",
                "inPartnerId": 1, "outPartnerId": 9, "isPrepaid": 1,
                "matchPrefixCustomer": "70", "callRatePerMinBDT": 0.50, "inPartnerUom": "BDT", "idPackageAccount": 3061,
                "inPartnerCost": 0.50, "packageAmount": 0,
                "additionalMetaData": "{\\"campaignId\\":12,\\"campaignName\\":\\"cola-eid\\",\\"contentId\\":\\"c-81\\",\\"app\\":\\"captive\\",\\"ruleId\\":3,\\"zone\\":\\"dhaka-north\\",\\"site\\":\\"mirpur-10\\",\\"requiredSeconds\\":10,\\"completed\\":true,\\"credited\\":true,\\"fallback\\":false,\\"levelIndex\\":0,\\"partnerName\\":\\"Unilever\\",\\"balanceBefore\\":120.00,\\"balanceAfter\\":119.50,\\"reserveRef\\":\\"2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa#L0\\"}" },

              { "tenant": "btcl", "resellerHierarchy": "btcl", "serviceGroup": 30,
                "sequenceNo": 1790961243000018,
                "callId": "2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa", "channelCallUuid": "2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa",
                "startTime": "2026-10-02 21:14:03", "answerTime": "2026-10-02 21:14:04", "endTime": "2026-10-02 21:14:14",
                "durationSec": 10,
                "originatingCallingNumber": "8801711000001", "terminatingCallingNumber": "8801711000001",
                "originatingCalledNumber": "7001", "terminatingCalledNumber": "7001",
                "callerIp": "10.20.0.1", "receiverIp": "10.10.188.40",
                "hangupCause": "NORMAL_CLEARING", "channelReadCodecName": "image", "pdd": 1.0,
                "incomingRoute": "cola-eid", "outgoingRoute": "dhaka-north",
                "inPartnerId": 44, "outPartnerId": 9, "isPrepaid": 1,
                "matchPrefixCustomer": "70", "callRatePerMinBDT": 0.40, "inPartnerUom": "BDT", "idPackageAccount": 3044,
                "inPartnerCost": 0.40, "packageAmount": 0,
                "additionalMetaData": "{\\"campaignId\\":12,\\"levelIndex\\":1,\\"partnerName\\":\\"R1\\",\\"reserveRef\\":\\"2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa#L1\\"}" }
            ]
            """;

    /** A view refused before anyone was admitted, as seed-callflow's {@code CdrAssembler.unadmittedRecord} sends it:
     * one record on the entry tenant, {@code answerTime} null, no rate, no unit, no prefix, no account. */
    static final String A_REFUSED_VIEW = """
            [ { "tenant": "btcl", "resellerHierarchy": "btcl", "serviceGroup": 30, "sequenceNo": 1790961243000031,
                "callId": "9d1e0f0a-0000-4000-8000-00000000r001", "channelCallUuid": "9d1e0f0a-0000-4000-8000-00000000r001",
                "startTime": "2026-10-02 21:20:00", "answerTime": null, "endTime": "2026-10-02 21:20:00",
                "durationSec": 0,
                "originatingCallingNumber": "aa:bb:cc:dd:ee:42", "terminatingCallingNumber": "aa:bb:cc:dd:ee:42",
                "originatingCalledNumber": "7009", "terminatingCalledNumber": "7009",
                "callerIp": "10.20.0.1", "hangupCause": "NO_RULE",
                "inPartnerId": 2, "isPrepaid": 0, "inPartnerCost": 0, "packageAmount": 0,
                "additionalMetaData": "{\\"app\\":\\"captive\\",\\"zone\\":\\"sylhet-09\\"}" } ]
            """;

    static ITenantRegistry registryWith(String... dbNames) {
        Map<String, Tenant> index = new HashMap<>();
        for (String db : dbNames) {
            Tenant t = new Tenant();
            t.Name = db;
            t.DbName = db;
            index.put(db, t);
        }
        return new ITenantRegistry() {
            @Override public boolean IsLoaded() { return true; }
            @Override public Tenant FindByDbName(String dbName) { return index.get(dbName); }
            @Override public List<Tenant> AncestorChain(String dbName) { return List.of(); }
            @Override public Collection<Tenant> Roots() { return index.values(); }
        };
    }

    private static cdr only(MultiTenantCdrBatch batch, String tenant) {
        for (PerTenantCdrs t : batch.tenants())
            if (t.tenant().equals(tenant)) {
                assertEquals(1, t.cdrs().size(), tenant + " holds one record");
                return t.cdrs().get(0);
            }
        throw new AssertionError("no slice for tenant " + tenant + " in " + batch.tenants());
    }

    @Test
    void the_briefs_sample_decodes_and_maps_tier_by_tier() {
        var pre = new CdrEventPreprocessor(registryWith("btcl", "res_44"));

        MultiTenantCdrBatch batch = pre.Preprocess(List.of(ONE_VIEW_TWO_TIERS));

        assertTrue(batch.deadLetters().isEmpty(), "no dead letter: " + batch.deadLetters());
        assertEquals(List.of("res_44", "btcl"), batch.tenants().stream().map(PerTenantCdrs::tenant).toList(),
                "the leaf first, as sent");

        cdr leaf = only(batch, "res_44");
        assertEquals(30, leaf.ServiceGroup);                                   // stated, taken as given
        assertEquals("cola-eid", leaf.IncomingRoute);                          // the wire's own route, not receiverIp
        assertEquals("dhaka-north", leaf.OutgoingRoute);                       // the wire's own route, not callerIp
        assertTrue(leaf.AdditionalMetaData.startsWith("{\"campaignId\":12,\"campaignName\":\"cola-eid\","),
                leaf.AdditionalMetaData);
        assertTrue(leaf.AdditionalMetaData.endsWith("\"reserveRef\":\"2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa#L0\"}"));
        assertEquals("2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa", leaf.ChannelCallUuid);
        assertEquals("2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa", leaf.UniqueBillId);
        assertEquals("btcl > res_44", leaf.ResellerHierarchy);
        assertEquals(1790961243000017L, leaf.SequenceNumber);
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 3), leaf.StartTime);
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 4), leaf.AnswerTime);
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 4), leaf.ConnectTime);
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 14), leaf.EndTime);
        assertEquals(0, new BigDecimal("10").compareTo(leaf.DurationSec));
        assertEquals("8801711000001", leaf.OriginatingCallingNumber);
        assertEquals("7001", leaf.OriginatingCalledNumber);
        assertEquals("10.20.0.1", leaf.OriginatingIP);
        assertEquals("10.10.188.40", leaf.TerminatingIP);
        assertEquals("NORMAL_CLEARING", leaf.HangupCause);
        assertEquals("NORMAL_CLEARING", leaf.AreaCodeOrLata);                  // where the call lane keeps it
        assertEquals("image", leaf.Codec);
        assertEquals(1.0f, leaf.PDD);
        assertEquals(1, leaf.InPartnerId);
        assertEquals(9, leaf.OutPartnerId);
        assertEquals(1, leaf.PrePaid);
        assertEquals("70", leaf.MatchedPrefixCustomer);
        assertEquals(0, new BigDecimal("0.50").compareTo(leaf.CustomerRate));
        assertEquals("BDT", leaf.InPartnerUom);
        assertEquals(3061L, leaf.IdPackageAccount);
        assertEquals(0, new BigDecimal("0.50").compareTo(leaf.InPartnerCost));
        assertEquals(0, BigDecimal.ZERO.compareTo(leaf.PackageAmount));

        cdr root = only(batch, "btcl");
        assertEquals(30, root.ServiceGroup);
        assertEquals("btcl", root.ResellerHierarchy);
        assertEquals(44, root.InPartnerId);                                    // the reseller pays the operator
        assertEquals(3044L, root.IdPackageAccount);
        assertEquals(0, new BigDecimal("0.40").compareTo(root.CustomerRate));
        assertEquals(0, new BigDecimal("0.40").compareTo(root.InPartnerCost));
        assertEquals("{\"campaignId\":12,\"levelIndex\":1,\"partnerName\":\"R1\","
                + "\"reserveRef\":\"2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa#L1\"}", root.AdditionalMetaData);
    }

    @Test
    void a_view_nobody_was_admitted_for_is_a_record_not_a_dead_letter() {
        var pre = new CdrEventPreprocessor(registryWith("btcl"));

        MultiTenantCdrBatch batch = pre.Preprocess(List.of(A_REFUSED_VIEW));

        assertTrue(batch.deadLetters().isEmpty(), "the producer sends no rate, unit or out-partner for a refused view: "
                + batch.deadLetters());
        cdr refused = only(batch, "btcl");
        assertEquals(30, refused.ServiceGroup);
        assertNull(refused.AnswerTime, "never shown");
        assertNull(refused.ConnectTime);
        assertEquals("NO_RULE", refused.HangupCause);
        assertEquals(2, refused.InPartnerId);
        assertNull(refused.OutPartnerId);
        assertNull(refused.CustomerRate);
        assertNull(refused.InPartnerUom);
        assertNull(refused.MatchedPrefixCustomer);
        assertNull(refused.IdPackageAccount);
        assertEquals(0, BigDecimal.ZERO.compareTo(refused.InPartnerCost));
        assertEquals(0, BigDecimal.ZERO.compareTo(refused.DurationSec));
    }

    @Test
    void a_record_that_names_no_partner_is_mediated_not_dead_lettered() {
        var pre = new CdrEventPreprocessor(registryWith("btcl"));
        String noPartner = A_REFUSED_VIEW.replace("\"inPartnerId\": 2, ", "");

        MultiTenantCdrBatch batch = pre.Preprocess(List.of(noPartner));

        assertTrue(batch.deadLetters().isEmpty(), "the checklist judges it (cdrerror), not the decoder: " + batch.deadLetters());
        assertNull(only(batch, "btcl").InPartnerId);
    }

    @Test
    void what_the_wire_still_requires_is_still_a_dead_letter() {
        var pre = new CdrEventPreprocessor(registryWith("btcl"));
        Map<String, String> without = Map.of(
                "\"channelCallUuid\": \"9d1e0f0a-0000-4000-8000-00000000r001\",", "missing channelCallUuid",
                "\"startTime\": \"2026-10-02 21:20:00\",", "missing startTime",
                "\"durationSec\": 0,", "missing durationSec",
                "\"inPartnerCost\": 0,", "missing inPartnerCost",
                ", \"packageAmount\": 0", "missing packageAmount");

        for (Map.Entry<String, String> e : without.entrySet()) {
            String broken = A_REFUSED_VIEW.replace(e.getKey(), "");
            assertTrue(!broken.equals(A_REFUSED_VIEW), "the fixture holds " + e.getKey());

            MultiTenantCdrBatch batch = pre.Preprocess(List.of(broken));

            assertEquals(1, batch.deadLetters().size(), e.getValue());
            assertEquals(e.getValue(), batch.deadLetters().get(0).reason());
        }
    }

    @Test
    void a_producer_that_sends_no_routes_and_no_meta_data_keeps_the_call_lanes_mapping() {
        // The live call feed (CdrEventPreprocessorTests.CALL_2_TIERS' shape): no incomingRoute / outgoingRoute /
        // additionalMetaData — the peer addresses stay the routes and the SIP Call-ID stays in AdditionalMetaData.
        var pre = new CdrEventPreprocessor(registryWith("telcobright"));
        String call = """
                [ { "tenant":"telcobright", "resellerHierarchy":"telcobright", "sequenceNo":7, "callId":"c7",
                    "channelCallUuid":"u7", "variableSipCallId":"sip-7",
                    "startTime":"2026-06-17 13:34:43","answerTime":"2026-06-17 13:34:54","endTime":"2026-06-17 13:34:56",
                    "durationSec":2.0, "originatingCallingNumber":"a","terminatingCallingNumber":"a",
                    "originatingCalledNumber":"b","terminatingCalledNumber":"b",
                    "callerIp":"103.95.96.78","receiverIp":"103.95.96.98",
                    "inPartnerId":1,"outPartnerId":2,"callRatePerMinBDT":0.1,"inPartnerUom":"BDT",
                    "inPartnerCost":0.0,"packageAmount":0.0 } ]
                """;

        cdr c = only(pre.Preprocess(List.of(call)), "telcobright");

        assertEquals("103.95.96.98", c.IncomingRoute);   // = receiverIp
        assertEquals("103.95.96.78", c.OutgoingRoute);   // = callerIp
        assertEquals("sip-7", c.AdditionalMetaData);
        assertEquals(0, c.ServiceGroup);                 // not stated: the pipeline detects it
        assertEquals("u7", c.ChannelCallUuid);
    }

    @Test
    void the_wires_meta_data_wins_over_the_sip_call_id() {
        var pre = new CdrEventPreprocessor(registryWith("btcl"));
        String both = A_REFUSED_VIEW.replace("\"callerIp\"", "\"variableSipCallId\": \"sip-9\", \"callerIp\"");

        cdr c = only(pre.Preprocess(List.of(both)), "btcl");

        assertEquals("{\"app\":\"captive\",\"zone\":\"sylhet-09\"}", c.AdditionalMetaData);
    }

    @Test
    void a_stated_30_is_taken_as_given_and_no_statement_is_left_for_detection() {
        var pre = new CdrEventPreprocessor(registryWith("btcl"));

        assertEquals(30, only(pre.Preprocess(List.of(A_REFUSED_VIEW)), "btcl").ServiceGroup);
        for (String noStatement : List.of("\"serviceGroup\": 0,", "\"serviceGroup\": null,", "")) {
            String record = A_REFUSED_VIEW.replace("\"serviceGroup\": 30,", noStatement);
            MultiTenantCdrBatch batch = pre.Preprocess(List.of(record));
            assertTrue(batch.deadLetters().isEmpty(), "'" + noStatement + "': " + batch.deadLetters());
            assertEquals(0, only(batch, "btcl").ServiceGroup, "'" + noStatement + "' states nothing: billing detects the group");
        }
    }

    @Test
    void a_record_that_states_a_group_billing_does_not_take_as_given_is_refused_in_words() {
        // A record that states a group is never re-classified by a guess (architect's ruling 2026-10-04). 10, 11 and
        // 15 are groups billing DETECTS; it has no lane that takes them as stated. 7 and -1 are no group at all.
        var pre = new CdrEventPreprocessor(registryWith("btcl"));

        for (int stated : List.of(10, 11, 15, 7, -1)) {
            String record = A_REFUSED_VIEW.replace("\"serviceGroup\": 30,", "\"serviceGroup\": " + stated + ",");

            MultiTenantCdrBatch batch = pre.Preprocess(List.of(record));

            assertTrue(batch.tenants().isEmpty(), "service group " + stated + " must not reach the pipeline");
            assertEquals(1, batch.deadLetters().size());
            assertTrue(batch.deadLetters().get(0).reason().startsWith(
                    "service group " + stated + " is not known to this billing-core"), batch.deadLetters().get(0).reason());
        }
    }
}
