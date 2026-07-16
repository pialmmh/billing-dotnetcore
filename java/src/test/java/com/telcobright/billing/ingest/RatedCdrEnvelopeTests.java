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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the LIVE wire shape (observed on the CCL {@code cdr} topic 2026-07-16, routesphere's Kafka CDR sink):
 * {@code {"sequenceNo":N,"cdr":{...}}}, one tenant leg per message, ISO-8601 UTC datetimes + local twins,
 * {@code billsec} as the billable duration, and FAILED legs with null answerTime/outPartnerId/rate/uom.
 * The fixtures below are VERBATIM records fetched from the topic (only whitespace added).
 */
class RatedCdrEnvelopeTests {

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

    /** Verbatim from the topic: an ANSWERED res_233_2 leg (off13). billsec 6.91, +06:00 local twins. */
    private static final String ANSWERED_LEG = """
            {"sequenceNo":1881184,"cdr":{"callId":"a077b494-94d1-4a5c-8094-9ba381207b28",
             "sessionId":"a077b494-94d1-4a5c-8094-9ba381207b28","partnerId":236,"partnerName":"res_02_user",
             "outPartnerId":234,"packageAccountId":3,"uom":"BDT","isPrepaid":1,
             "callerNumber":"09646888888","calledNumber":"09638383838","callerIp":"103.95.96.22",
             "receiverIp":"103.95.96.98","inbound":false,"callType":"VOICE",
             "startTime":"2026-07-15T10:15:46.465Z","answerTime":"2026-07-15T10:15:46.832Z",
             "endTime":"2026-07-15T10:15:53.743460275Z","ringDurationSeconds":0.0,"billsec":6.91,
             "totalDurationSeconds":7.278,"callStatus":"NO_ANSWER","hangupCause":"NORMAL_CLEARING",
             "answered":true,"rate":0.4,"reservedAmount":0.4,"chargedAmount":0.040000,
             "returnedAmount":0.360000,"balanceBefore":10.0,"balanceAfter":9.960000,"inPartnerCost":0.04,
             "costIcxIn":0.004606666666666667,"costAnsIn":0.011516666666666668,"revenueAnsOut":0.0,
             "revenueIgwOut":0.0,"packageAmount":0.0,"supplierCost":0.0,"ansIdTerm":19,
             "ansPrefixTerm":"9638","ansIdOrig":0,"ansPrefixOrig":null,"serviceGroup":10,
             "terminatingCallingNumber":"09646888888","terminatingCalledNumber":"09638383838",
             "matchPrefixCustomer":"096","channelReadCodecName":"PCMA",
             "variableSipCallId":"06cd6fb3f2074cfa85c63199a0d792c0","pdd":0.366,"tenantName":"res_233_2",
             "createdAt":"2026-07-15T10:15:53.743428456Z","audioFilePath":null,
             "resellerHierarchy":"telcobright > res_233 > res_233_2",
             "startTimeLocal":"2026-07-15T16:15:46.465","endTimeLocal":"2026-07-15T16:15:53.743460275"}}""";

    /** Verbatim from the topic: a FAILED leg (off9) — no answerTime/outPartnerId/rate/uom/isPrepaid. */
    private static final String FAILED_LEG = """
            {"sequenceNo":1881180,"cdr":{"callId":"db0f0a6d-aab3-4606-a737-c6d535bb014e",
             "sessionId":"db0f0a6d-aab3-4606-a737-c6d535bb014e","partnerId":236,"outPartnerId":null,
             "packageAccountId":null,"uom":null,"isPrepaid":null,"callerNumber":"09646888888",
             "calledNumber":"09638383838","callerIp":"103.95.96.22","receiverIp":null,"inbound":false,
             "callType":"VOICE","startTime":"2026-07-15T10:10:47.579Z","answerTime":null,
             "endTime":"2026-07-15T10:10:47.698209353Z","billsec":0.0,"totalDurationSeconds":0.119,
             "callStatus":"FAILED","hangupCause":"INSUFFICIENT_BALANCE","answered":false,"rate":null,
             "chargedAmount":0,"returnedAmount":0,"inPartnerCost":0.0,"costIcxIn":0.0,"costAnsIn":0.0,
             "revenueAnsOut":0.0,"revenueIgwOut":0.0,"packageAmount":0.0,"supplierCost":0.0,
             "ansIdTerm":0,"ansPrefixTerm":null,"ansIdOrig":0,"ansPrefixOrig":null,"serviceGroup":0,
             "terminatingCallingNumber":"09646888888","terminatingCalledNumber":"09638383838",
             "matchPrefixCustomer":null,"variableSipCallId":"2d9e5dc0d8b849eba824d73aed8fd81e","pdd":null,
             "tenantName":"res_233_2","resellerHierarchy":"telcobright > res_233 > res_233_2",
             "startTimeLocal":"2026-07-15T16:10:47.579","endTimeLocal":"2026-07-15T16:10:47.698209353"}}""";

    @Test
    void live_answered_leg_decodes_maps_and_converts_to_local_time() {
        var pre = new CdrEventPreprocessor(registryWith("res_233_2"));
        MultiTenantCdrBatch batch = pre.Preprocess(List.of(ANSWERED_LEG));

        assertEquals(0, batch.deadLetters().size(), () -> "dead: " + batch.deadLetters());
        assertEquals(1, batch.tenants().size());
        PerTenantCdrs t = batch.tenants().get(0);
        assertEquals("res_233_2", t.tenant());

        cdr c = t.cdrs().get(0);
        assertEquals(1881184L, c.SequenceNumber);
        assertEquals("a077b494-94d1-4a5c-8094-9ba381207b28", c.UniqueBillId);
        assertEquals("a077b494-94d1-4a5c-8094-9ba381207b28", c.ChannelCallUuid);
        assertEquals("telcobright > res_233 > res_233_2", c.ResellerHierarchy);
        // live-schema NOT NULLs the wire doesn't carry: provenance marker + signaling start = leg start
        assertEquals("kafka:cdr", c.FileName);
        assertEquals(LocalDateTime.parse("2026-07-15T16:15:46.465"), c.SignalingStartTime);
        // LOCAL wall-clock (+06 twins), answerTime shifted by the same offset
        assertEquals(LocalDateTime.parse("2026-07-15T16:15:46.465"), c.StartTime);
        assertEquals(LocalDateTime.parse("2026-07-15T16:15:46.832"), c.AnswerTime);
        assertEquals(LocalDateTime.parse("2026-07-15T16:15:53.743460275"), c.EndTime);
        assertEquals(0, new BigDecimal("6.91").compareTo(c.DurationSec));   // billsec, not total
        assertEquals("09646888888", c.OriginatingCallingNumber);
        assertEquals("09638383838", c.OriginatingCalledNumber);
        assertEquals(236, c.InPartnerId);
        assertEquals(234, c.OutPartnerId);
        assertEquals(1, c.PrePaid);
        assertEquals("BDT", c.InPartnerUom);
        assertEquals(3L, c.IdPackageAccount);
        assertEquals(0, new BigDecimal("0.4").compareTo(c.CustomerRate));
        assertEquals(0, new BigDecimal("0.04").compareTo(c.InPartnerCost));
        assertEquals("096", c.MatchedPrefixCustomer);
        assertEquals("PCMA", c.Codec);
    }

    @Test
    void live_failed_leg_is_a_zero_charge_record_not_a_dead_letter() {
        var pre = new CdrEventPreprocessor(registryWith("res_233_2"));
        MultiTenantCdrBatch batch = pre.Preprocess(List.of(FAILED_LEG));

        assertEquals(0, batch.deadLetters().size(), () -> "dead: " + batch.deadLetters());
        cdr c = batch.tenants().get(0).cdrs().get(0);
        assertEquals(1881180L, c.SequenceNumber);
        assertNull(c.AnswerTime);                                            // unanswered
        assertEquals(0, BigDecimal.ZERO.compareTo(c.DurationSec));           // billsec 0
        assertEquals(0, c.OutPartnerId);                                     // no egress partner -> 0
        assertEquals(0, BigDecimal.ZERO.compareTo(c.CustomerRate));          // no admission rate -> 0
        assertEquals("BDT", c.InPartnerUom);                                 // cash default
        assertEquals("INSUFFICIENT_BALANCE", c.HangupCause);
    }

    @Test
    void object_without_cdr_payload_dead_letters() {
        var pre = new CdrEventPreprocessor(registryWith("res_233_2"));
        MultiTenantCdrBatch batch = pre.Preprocess(List.of("{\"hello\":\"world\"}"));
        assertEquals(0, batch.tenants().size());
        assertEquals(1, batch.deadLetters().size());
        assertTrue(batch.deadLetters().get(0).reason().startsWith("decode failed"));
    }
}
