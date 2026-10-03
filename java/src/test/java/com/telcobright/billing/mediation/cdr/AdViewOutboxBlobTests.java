package com.telcobright.billing.mediation.cdr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sql.ISqlExecutor;
import com.telcobright.billing.testsupport.AdViewSamples;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B9 — the outbox row and its blob are UNCHANGED by the ad view: entity {@code cdr}, {@code op: add}, ONE row per
 * tenant batch, blob v2 ({@code [{Cdr, Chargeables[]}…]}, JSON → gzip → base64). A group-30 entry is an ordinary
 * entry whose {@code Cdr} has {@code ServiceGroup: 30} and {@code AdditionalMetaData}.
 *
 * <p>The blob is read here THE WAY THE SUMMARY SERVICE READS IT: its own codec (base64 → gunzip), its own mapper
 * settings (case-insensitive property names, unknown fields ignored, java.time) and records shaped as its
 * {@code CdrBlobEntry} / {@code Cdr} / {@code Chargeable} — not with billing-core's own decoder, which would only
 * prove that billing can read what billing wrote. The eight facts the summary agent's ad beans read (architect's
 * answer to BC-0001, §3) are asserted one by one.
 */
class AdViewOutboxBlobTests {

    // ── the summary service's side, mirrored ─────────────────────────────────────────────────────────────────

    /** summary-service's {@code CdrBlobMapper}: case-insensitive, lenient on unknown fields, java.time. */
    private static final ObjectMapper SummarySide = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlobEntry(BlobCdr cdr, List<BlobLeg> chargeables) {}

    /** The fields its call build reads (its {@code Cdr} record) and the ones its ad beans read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlobCdr(int switchId, Integer inPartnerId, Integer outPartnerId, String incomingRoute, String outgoingRoute,
                   Integer chargingStatus, BigDecimal durationSec, BigDecimal roundedDuration, BigDecimal duration1,
                   LocalDateTime startTime, LocalDateTime answerTime, LocalDateTime connectTime, String matchedPrefixCustomer,
                   int serviceGroup, String hangupCause, String additionalMetaData, String originatingCalledNumber, String codec) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlobLeg(int servicegroup, int servicefamily, Integer assignedDirection, String idBilledUom, String prefix,
                   LocalDateTime transactionTime, BigDecimal unitPriceOrCharge, BigDecimal billedAmount, BigDecimal quantity) {}

    /** summary-service's {@code OutboxCodec.decode}: base64 → gunzip → the JSON bytes. */
    private static List<BlobEntry> AsTheSummaryServiceReads(String data) throws Exception {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            return SummarySide.readValue(in.readAllBytes(),
                    SummarySide.getTypeFactory().constructCollectionType(List.class, BlobEntry.class));
        }
    }

    // ── billing-core's side ──────────────────────────────────────────────────────────────────────────────────

    private static final class InMemorySql implements ISqlExecutor {
        final List<String> Executed = new ArrayList<>();
        @Override public int ExecuteNonQuery(String sql) { Executed.add(sql); return 1; }

        List<String> OutboxInserts() { return Executed.stream().filter(s -> s.startsWith("insert into summary_affected")).toList(); }
    }

    /** One tenant batch through the pipeline; the outbox statements it emitted. */
    private static List<String> OutboxOf(List<cdr> batch) {
        var sql = new InMemorySql();
        CdrPipeline.Default().Process(new CdrBatch(TestData.fixture().mediation(), Map.of(), batch, sql));
        return sql.OutboxInserts();
    }

    /** The {@code data} value of the one outbox insert. */
    private static String DataOf(String insert) {
        return insert.substring(insert.lastIndexOf(", '") + 3, insert.length() - 2);
    }

    private static List<cdr> TheRootsBatch() {
        List<cdr> batch = new ArrayList<>(AdViewSamples.cdrsOf("btcl", AdViewSamples.ONE_VIEW_TWO_TIERS));
        batch.addAll(AdViewSamples.cdrsOf("btcl", AdViewSamples.A_REFUSED_VIEW));
        return batch;
    }

    @Test
    void the_outbox_row_is_the_calls_entity_cdr_op_add_one_row_a_tenant_batch() {
        List<String> outbox = OutboxOf(TheRootsBatch());

        assertEquals(1, outbox.size(), "ONE row for the tenant's batch of two records");
        assertTrue(outbox.get(0).startsWith("insert into summary_affected (entity_type, op, data) values ('cdr', 'add', '"),
                outbox.get(0).substring(0, 90));
    }

    @Test
    void the_summary_service_reads_a_shown_view_out_of_the_blob() throws Exception {
        List<BlobEntry> blob = AsTheSummaryServiceReads(DataOf(OutboxOf(TheRootsBatch()).get(0)));

        assertEquals(2, blob.size());
        BlobEntry shown = blob.get(0);
        assertEquals(30, shown.cdr().serviceGroup());                                            // fact 1
        assertEquals("NORMAL_CLEARING", shown.cdr().hangupCause());                              // fact 2
        assertEquals("{\"campaignId\":12,\"levelIndex\":1,\"partnerName\":\"R1\",\"reserveRef\":\""
                + AdViewSamples.VIEW_ID + "#L1\"}", shown.cdr().additionalMetaData());            // fact 3: character for character
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 4), shown.cdr().answerTime());         // fact 4
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 3), shown.cdr().startTime());
        assertEquals(0, new BigDecimal("10").compareTo(shown.cdr().durationSec()));
        assertEquals(44, shown.cdr().inPartnerId());
        assertEquals("7001", shown.cdr().originatingCalledNumber());
        assertEquals("image", shown.cdr().codec());
        assertEquals(1, shown.cdr().chargingStatus());
        assertEquals("cola-eid", shown.cdr().incomingRoute());
        assertEquals("dhaka-north", shown.cdr().outgoingRoute());

        assertEquals(1, shown.chargeables().size(), "ONE customer chargeable per record");        // fact 5
        BlobLeg leg = shown.chargeables().get(0);
        assertEquals(30, leg.servicegroup());
        assertEquals(1, leg.assignedDirection());
        assertEquals(0, new BigDecimal("0.40").compareTo(leg.billedAmount()));
        assertEquals("BDT", leg.idBilledUom());
        assertEquals(0, new BigDecimal("0.40").compareTo(leg.unitPriceOrCharge()));
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 14, 3), leg.transactionTime());
        assertEquals("70", leg.prefix());
    }

    @Test
    void a_failed_view_is_in_the_blob_as_a_cdr_and_a_chargeable_of_zero() throws Exception {
        BlobEntry refused = AsTheSummaryServiceReads(DataOf(OutboxOf(TheRootsBatch()).get(0))).get(1);

        assertEquals(30, refused.cdr().serviceGroup());                                          // fact 6
        assertNull(refused.cdr().answerTime(), "null = never shown (the field is left out of the blob)");
        assertNull(refused.cdr().connectTime());
        assertEquals(0, refused.cdr().chargingStatus());
        assertEquals("NO_RULE", refused.cdr().hangupCause());
        assertEquals(1, refused.chargeables().size());
        BlobLeg zero = refused.chargeables().get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.billedAmount()));
        assertEquals("BDT", zero.idBilledUom(), "money 0: it must never open a units row in the summary");
        assertEquals(1, zero.assignedDirection());
        assertEquals(LocalDateTime.of(2026, 10, 2, 21, 20, 0), zero.transactionTime(), "a leg without a transaction time is skipped there");
    }

    @Test
    void a_view_paid_in_units_is_told_from_money_by_its_unit() throws Exception {
        cdr view = AdViewSamples.cdrsOf("res_44", AdViewSamples.ONE_VIEW_TWO_TIERS).get(0);
        view.InPartnerCost = BigDecimal.ZERO;
        view.PackageAmount = new BigDecimal("1");
        view.InPartnerUom = "OTH_ea";

        BlobLeg leg = AsTheSummaryServiceReads(DataOf(OutboxOf(List.of(view)).get(0))).get(0).chargeables().get(0);

        assertEquals("OTH_ea", leg.idBilledUom());
        assertEquals(0, new BigDecimal("1").compareTo(leg.billedAmount()));
    }

    @Test
    void a_record_that_went_to_cdrerror_has_no_share_in_the_outbox() {
        cdr noPartner = AdViewSamples.cdrsOf("btcl", AdViewSamples.A_REFUSED_VIEW).get(0);
        noPartner.InPartnerId = null;

        assertTrue(OutboxOf(List.of(noPartner)).isEmpty(), "a batch with nothing rated writes no outbox row");
    }

    @Test
    void the_blob_keeps_billing_cores_property_names_and_leaves_nulls_out() throws Exception {
        String data = DataOf(OutboxOf(TheRootsBatch()).get(0));
        JsonNode raw;
        try (var in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            raw = new ObjectMapper().readTree(in.readAllBytes());
        }

        JsonNode shown = raw.get(0), refused = raw.get(1);
        assertTrue(shown.has("Cdr") && shown.has("Chargeables"), "blob v2: {Cdr, Chargeables[]}");
        for (String name : List.of("ServiceGroup", "HangupCause", "AdditionalMetaData", "AnswerTime", "StartTime", "DurationSec",
                "InPartnerId", "OriginatingCalledNumber", "Codec", "ChannelCallUuid", "ResellerHierarchy", "InPartnerUom", "PackageAmount"))
            assertTrue(shown.get("Cdr").has(name), "Cdr." + name);
        assertFalse(refused.get("Cdr").has("AnswerTime"), "a null is left out, not written as null");
        assertFalse(refused.get("Cdr").has("CustomerRate"));
        for (String name : List.of("servicegroup", "assignedDirection", "BilledAmount", "idBilledUom", "unitPriceOrCharge", "transactionTime"))
            assertTrue(shown.get("Chargeables").get(0).has(name), "Chargeables[0]." + name);
    }
}
