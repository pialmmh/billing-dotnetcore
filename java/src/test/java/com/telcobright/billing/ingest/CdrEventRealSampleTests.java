package com.telcobright.billing.ingest;

import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the preprocessor against the REAL {@code cdr}-topic payloads routesphere produces (provided 2026-07-07):
 * Call 1 = a 2-tier outgoing call, Call 2 = a 3-tier incoming call whose leaf leg is billed by PACKAGE
 * ({@code inPartnerUom=TF_min}). Proves decode, the {@code yyyy-MM-dd HH:mm:ss} datetimes, the full
 * {@code CdrEvent}→{@code cdr} mapping (incl. the 6 new columns), and tenant grouping across a multi-call
 * poll-batch — all against the exact wire format, not a hand-made one.
 */
class CdrEventRealSampleTests {

    private static ITenantRegistry registryWith(String... dbNames) {
        Map<String, Tenant> index = new java.util.HashMap<>();
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

    // Call 1 — OUTGOING, 2 tiers (res_233, telcobright), answered 2.055s. Verbatim from the provided sample.
    private static final String CALL_1 = """
            [
              { "sequenceNo": 1881104,
                "originatingCallingNumber": "09646999999", "terminatingCallingNumber": "09646999999",
                "originatingCalledNumber": "8801789896378", "terminatingCalledNumber": "8801789896378",
                "startTime": "2026-06-17 13:34:43", "answerTime": "2026-06-17 13:34:54", "endTime": "2026-06-17 13:34:56",
                "durationSec": 2.055, "channelCallUuid": "7def7167-dad1-4215-8680-a3e0d24d1b6a", "hangupCause": "NORMAL_CLEARING",
                "callerIp": "103.95.96.78", "receiverIp": "103.95.96.98", "tenant": "res_233",
                "supplierPrefix": "", "supplierCost": 0.0, "isPrepaid": 1,
                "inPartnerCost": 0.012, "inPartnerUom": "BDT", "costIcxIn": 0.001, "costAnsIn": 0.003,
                "revenueAnsOut": 0.0, "revenueIgwOut": 0.0, "packageAmount": 0.0,
                "inPartnerId": 1, "outPartnerId": 234,
                "ansIdTerm": 23, "ansPrefixTerm": "88017", "ansIdOrig": 0, "ansPrefixOrig": "",
                "matchPrefixCustomer": "880", "callRatePerMinBDT": 0.350, "idPackageAccount": 1,
                "resellerHierarchy": "telcobright > res_233", "channelReadCodecName": "PCMU",
                "callId": "2-169791@103.95.96.78", "pdd": 2.447 },
              { "sequenceNo": 1881105,
                "originatingCallingNumber": "09646999999", "terminatingCallingNumber": "09646999999",
                "originatingCalledNumber": "8801789896378", "terminatingCalledNumber": "8801789896378",
                "startTime": "2026-06-17 13:34:43", "answerTime": "2026-06-17 13:34:54", "endTime": "2026-06-17 13:34:56",
                "durationSec": 2.055, "channelCallUuid": "7def7167-dad1-4215-8680-a3e0d24d1b6a", "hangupCause": "NORMAL_CLEARING",
                "callerIp": "103.95.96.78", "receiverIp": "103.95.96.98", "tenant": "telcobright",
                "supplierPrefix": "", "supplierCost": 0.0, "isPrepaid": 2,
                "inPartnerCost": 0.013, "inPartnerUom": "BDT", "costIcxIn": 0.001, "costAnsIn": 0.003,
                "revenueAnsOut": 0.0, "revenueIgwOut": 0.0, "packageAmount": 0.0,
                "inPartnerId": 233, "outPartnerId": 234,
                "ansIdTerm": 23, "ansPrefixTerm": "88017", "ansIdOrig": 0, "ansPrefixOrig": "",
                "matchPrefixCustomer": "880", "callRatePerMinBDT": 0.400, "idPackageAccount": 236,
                "resellerHierarchy": "telcobright", "channelReadCodecName": "PCMU",
                "callId": "2-169791@103.95.96.78", "pdd": 2.447 }
            ]
            """;

    // Call 2 — INCOMING, 3 tiers (res_233_2 [PACKAGE leaf], res_233, telcobright), answered 2.056s.
    private static final String CALL_2 = """
            [
              { "sequenceNo": 1881101,
                "originatingCallingNumber": "8801712345678", "terminatingCallingNumber": "88012345678",
                "originatingCalledNumber": "09646888888", "terminatingCalledNumber": "09646888888",
                "startTime": "2026-06-17 13:31:50", "answerTime": "2026-06-17 13:31:52", "endTime": "2026-06-17 13:31:54",
                "durationSec": 2.056, "channelCallUuid": "6c9b212b-ee18-4d0a-a3aa-ea25e4a63b9f", "hangupCause": "NORMAL_CLEARING",
                "callerIp": "103.95.96.78", "receiverIp": "103.95.96.22", "tenant": "res_233_2",
                "supplierPrefix": "", "supplierCost": 0.0, "isPrepaid": 1,
                "inPartnerCost": 0.0, "inPartnerUom": "TF_min", "costIcxIn": 0.0, "costAnsIn": 0.0,
                "revenueAnsOut": 0.003, "revenueIgwOut": 0.001, "packageAmount": 0.034,
                "inPartnerId": 234, "outPartnerId": 236,
                "ansIdTerm": 0, "ansPrefixTerm": "", "ansIdOrig": 23, "ansPrefixOrig": "88017",
                "matchPrefixCustomer": "096", "callRatePerMinBDT": 0.400, "idPackageAccount": 2,
                "resellerHierarchy": "telcobright > res_233 > res_233_2", "channelReadCodecName": "PCMU",
                "callId": "1-169340@103.95.96.78", "pdd": 0.146 },
              { "sequenceNo": 1881102,
                "originatingCallingNumber": "8801712345678", "terminatingCallingNumber": "88012345678",
                "originatingCalledNumber": "09646888888", "terminatingCalledNumber": "09646888888",
                "startTime": "2026-06-17 13:31:50", "answerTime": "2026-06-17 13:31:52", "endTime": "2026-06-17 13:31:54",
                "durationSec": 2.056, "channelCallUuid": "6c9b212b-ee18-4d0a-a3aa-ea25e4a63b9f", "hangupCause": "NORMAL_CLEARING",
                "callerIp": "103.95.96.78", "receiverIp": "103.95.96.22", "tenant": "res_233",
                "supplierPrefix": "", "supplierCost": 0.0, "isPrepaid": 1,
                "inPartnerCost": 0.0, "inPartnerUom": "BDT", "costIcxIn": 0.0, "costAnsIn": 0.0,
                "revenueAnsOut": 0.003, "revenueIgwOut": 0.001, "packageAmount": 0.0,
                "inPartnerId": 234, "outPartnerId": 2,
                "ansIdTerm": 0, "ansPrefixTerm": "", "ansIdOrig": 23, "ansPrefixOrig": "88017",
                "matchPrefixCustomer": "096", "callRatePerMinBDT": 0.0, "idPackageAccount": 2,
                "resellerHierarchy": "telcobright > res_233", "channelReadCodecName": "PCMU",
                "callId": "1-169340@103.95.96.78", "pdd": 0.146 },
              { "sequenceNo": 1881103,
                "originatingCallingNumber": "8801712345678", "terminatingCallingNumber": "88012345678",
                "originatingCalledNumber": "09646888888", "terminatingCalledNumber": "09646888888",
                "startTime": "2026-06-17 13:31:50", "answerTime": "2026-06-17 13:31:52", "endTime": "2026-06-17 13:31:54",
                "durationSec": 2.056, "channelCallUuid": "6c9b212b-ee18-4d0a-a3aa-ea25e4a63b9f", "hangupCause": "NORMAL_CLEARING",
                "callerIp": "103.95.96.78", "receiverIp": "103.95.96.22", "tenant": "telcobright",
                "supplierPrefix": "", "supplierCost": 0.0, "isPrepaid": 2,
                "inPartnerCost": 0.0, "inPartnerUom": "BDT", "costIcxIn": 0.0, "costAnsIn": 0.0,
                "revenueAnsOut": 0.003, "revenueIgwOut": 0.001, "packageAmount": 0.0,
                "inPartnerId": 234, "outPartnerId": 233,
                "ansIdTerm": 0, "ansPrefixTerm": "", "ansIdOrig": 23, "ansPrefixOrig": "88017",
                "matchPrefixCustomer": "096", "callRatePerMinBDT": 0.0, "idPackageAccount": 236,
                "resellerHierarchy": "telcobright", "channelReadCodecName": "PCMU",
                "callId": "1-169340@103.95.96.78", "pdd": 0.146 }
            ]
            """;

    private static cdr firstOf(MultiTenantCdrBatch b, String tenant) {
        return b.tenants().stream().filter(t -> t.tenant().equals(tenant)).findFirst().orElseThrow().cdrs().get(0);
    }

    @Test
    void call1_outgoing_maps_both_tiers() {
        var pre = new CdrEventPreprocessor(registryWith("res_233", "telcobright"));
        MultiTenantCdrBatch b = pre.Preprocess(List.of(CALL_1));

        assertTrue(b.deadLetters().isEmpty(), () -> "unexpected dead letters: " + b.deadLetters());
        assertEquals(2, b.tenants().size());

        cdr c = firstOf(b, "res_233");
        assertEquals(1881104L, c.SequenceNumber);
        assertEquals("2-169791@103.95.96.78", c.UniqueBillId);
        assertEquals("7def7167-dad1-4215-8680-a3e0d24d1b6a", c.ChannelCallUuid);
        assertEquals("telcobright > res_233", c.ResellerHierarchy);
        assertEquals(LocalDateTime.of(2026, 6, 17, 13, 34, 43), c.StartTime);
        assertEquals(LocalDateTime.of(2026, 6, 17, 13, 34, 54), c.AnswerTime);
        assertEquals(LocalDateTime.of(2026, 6, 17, 13, 34, 56), c.EndTime);
        assertEquals(0, new BigDecimal("2.055").compareTo(c.DurationSec));
        assertEquals("103.95.96.78", c.OriginatingIP);
        assertEquals("103.95.96.98", c.TerminatingIP);
        assertEquals("NORMAL_CLEARING", c.HangupCause);
        assertEquals("PCMU", c.Codec);
        assertEquals(1, c.InPartnerId);
        assertEquals(234, c.OutPartnerId);
        assertEquals(1, c.PrePaid);
        assertEquals("880", c.MatchedPrefixCustomer);
        assertEquals("BDT", c.InPartnerUom);
        assertEquals(1L, c.IdPackageAccount);
        assertEquals(0, new BigDecimal("0.350").compareTo(c.CustomerRate));   // admission reference
        assertEquals(0, new BigDecimal("0.012").compareTo(c.InPartnerCost));
        assertEquals(23, c.AnsIdTerm);
        assertEquals("88017", c.AnsPrefixTerm);
        assertEquals(2.447f, c.PDD);
    }

    @Test
    void call2_incoming_package_leaf_maps_uom_and_package_amount() {
        var pre = new CdrEventPreprocessor(registryWith("res_233_2", "res_233", "telcobright"));
        MultiTenantCdrBatch b = pre.Preprocess(List.of(CALL_2));

        assertTrue(b.deadLetters().isEmpty(), () -> "unexpected dead letters: " + b.deadLetters());
        assertEquals(3, b.tenants().size());

        cdr leaf = firstOf(b, "res_233_2");                 // the package-billed leg
        assertEquals(1881101L, leaf.SequenceNumber);
        assertEquals("telcobright > res_233 > res_233_2", leaf.ResellerHierarchy);
        assertEquals("TF_min", leaf.InPartnerUom);
        assertEquals(0, new BigDecimal("0.034").compareTo(leaf.PackageAmount));
        assertEquals(2L, leaf.IdPackageAccount);
        assertEquals(0, new BigDecimal("0.0").compareTo(leaf.InPartnerCost));
        assertEquals(234, leaf.InPartnerId);
        assertEquals(236, leaf.OutPartnerId);
        assertEquals("096", leaf.MatchedPrefixCustomer);
        assertEquals(23, leaf.AnsIdOrig);
        assertEquals("88017", leaf.AnsPrefixOrig);

        cdr root = firstOf(b, "telcobright");
        assertEquals(2, root.PrePaid);                      // postpaid at the operator tier
        assertEquals(0, new BigDecimal("0.0").compareTo(root.CustomerRate));
    }

    @Test
    void multi_call_poll_batch_groups_by_tenant() {
        var pre = new CdrEventPreprocessor(registryWith("res_233_2", "res_233", "telcobright"));
        MultiTenantCdrBatch b = pre.Preprocess(List.of(CALL_1, CALL_2));   // one poll = two calls

        assertTrue(b.deadLetters().isEmpty());
        assertEquals(3, b.tenants().size());
        assertEquals(2, b.tenants().stream().filter(t -> t.tenant().equals("res_233")).findFirst().orElseThrow().cdrs().size());
        assertEquals(2, b.tenants().stream().filter(t -> t.tenant().equals("telcobright")).findFirst().orElseThrow().cdrs().size());
        assertEquals(1, b.tenants().stream().filter(t -> t.tenant().equals("res_233_2")).findFirst().orElseThrow().cdrs().size());
    }
}
