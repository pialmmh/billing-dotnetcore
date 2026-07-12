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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the pure Contract-B transform (contract §3): decode the {@code cdr_rated} value (a JSON array of
 * per-tier records) → validate → map {@code CdrEvent}→{@code cdr} → group by tenant → attach the registry
 * context; bad records dead-letter without poisoning the batch. Fixtures mirror the contract §11 sample
 * (a 2-tier outgoing call), completed to the full required-field set (§2).
 */
class CdrEventPreprocessorTests {

    // A minimal in-memory registry: only known dbNames resolve (mirrors ITenantRegistry.FindByDbName).
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

    // The contract §11 call, both tiers complete (the sample abbreviates tier 2; the wire carries all §2 ✓ fields).
    private static final String CALL_2_TIERS = """
            [
              { "tenant":"res_233", "resellerHierarchy":"telcobright > res_233",
                "sequenceNo":1881104, "callId":"2-169791@103.95.96.78",
                "channelCallUuid":"7def7167-dad1-4215-8680-a3e0d24d1b6a",
                "startTime":"2026-06-17 13:34:43","answerTime":"2026-06-17 13:34:54","endTime":"2026-06-17 13:34:56",
                "durationSec":2.055,
                "originatingCallingNumber":"09646999999","terminatingCallingNumber":"09646999999",
                "originatingCalledNumber":"8801789896378","terminatingCalledNumber":"8801789896378",
                "callerIp":"103.95.96.78","receiverIp":"103.95.96.98",
                "inPartnerId":1,"outPartnerId":234,"isPrepaid":1,
                "matchPrefixCustomer":"880","callRatePerMinBDT":0.350,"inPartnerUom":"BDT","idPackageAccount":1,
                "inPartnerCost":0.012,"packageAmount":0.0,"supplierCost":0.010,
                "ansIdTerm":23,"ansPrefixTerm":"88017","channelReadCodecName":"PCMU","pdd":2.447,
                "hangupCause":"NORMAL_CLEARING" },

              { "tenant":"telcobright", "resellerHierarchy":"telcobright",
                "sequenceNo":1881105, "callId":"2-169791@103.95.96.78",
                "channelCallUuid":"7def7167-dad1-4215-8680-a3e0d24d1b6a",
                "startTime":"2026-06-17 13:34:43","answerTime":"2026-06-17 13:34:54","endTime":"2026-06-17 13:34:56",
                "durationSec":2.055,
                "originatingCallingNumber":"09646999999","terminatingCallingNumber":"09646999999",
                "originatingCalledNumber":"8801789896378","terminatingCalledNumber":"8801789896378",
                "inPartnerId":233,"outPartnerId":234,"isPrepaid":2,
                "matchPrefixCustomer":"880","callRatePerMinBDT":0.400,"inPartnerUom":"BDT","idPackageAccount":236,
                "inPartnerCost":0.013,"packageAmount":0.0 }
            ]
            """;

    @Test
    void two_tier_call_groups_into_two_tenants_and_maps_fields() {
        ITenantRegistry registry = registryWith("res_233", "telcobright");
        var pre = new CdrEventPreprocessor(registry);

        MultiTenantCdrBatch batch = pre.Preprocess(List.of(CALL_2_TIERS));

        assertTrue(batch.deadLetters().isEmpty(), "no dead letters expected");
        assertEquals(2, batch.tenants().size());

        // group order = first-seen: res_233 then telcobright.
        PerTenantCdrs res = batch.tenants().get(0);
        assertEquals("res_233", res.tenant());
        assertSame(registry.FindByDbName("res_233"), res.context());
        assertEquals(1, res.cdrs().size());

        cdr c = res.cdrs().get(0);
        assertEquals(1881104L, c.SequenceNumber);
        assertEquals("2-169791@103.95.96.78", c.UniqueBillId);
        assertEquals("7def7167-dad1-4215-8680-a3e0d24d1b6a", c.ChannelCallUuid);
        assertEquals("telcobright > res_233", c.ResellerHierarchy);
        assertEquals(LocalDateTime.of(2026, 6, 17, 13, 34, 43), c.StartTime);
        assertEquals(LocalDateTime.of(2026, 6, 17, 13, 34, 56), c.EndTime);
        assertEquals(0, new BigDecimal("2.055").compareTo(c.DurationSec));
        assertEquals("103.95.96.78", c.OriginatingIP);
        assertEquals("103.95.96.98", c.TerminatingIP);
        assertEquals(1, c.InPartnerId);
        assertEquals(234, c.OutPartnerId);
        assertEquals(1, c.PrePaid);
        assertEquals("880", c.MatchedPrefixCustomer);
        assertEquals("BDT", c.InPartnerUom);
        assertEquals(1L, c.IdPackageAccount);
        assertEquals("NORMAL_CLEARING", c.HangupCause);
        assertEquals("PCMU", c.Codec);
        assertEquals(0, new BigDecimal("0.350").compareTo(c.CustomerRate));   // reference (admission)
        assertEquals(0, new BigDecimal("0.012").compareTo(c.InPartnerCost));
        assertEquals(0, new BigDecimal("0.010").compareTo(c.OutPartnerCost)); // PROPOSED: supplierCost target

        assertEquals("telcobright", batch.tenants().get(1).tenant());
        assertEquals(1, batch.tenants().get(1).cdrs().size());
    }

    @Test
    void unknown_tenant_is_dead_lettered_not_thrown() {
        var pre = new CdrEventPreprocessor(registryWith("telcobright"));   // res_233 NOT registered
        MultiTenantCdrBatch batch = pre.Preprocess(List.of(CALL_2_TIERS));

        // res_233 tier dead-lettered; telcobright tier still written.
        assertEquals(1, batch.tenants().size());
        assertEquals("telcobright", batch.tenants().get(0).tenant());
        assertEquals(1, batch.deadLetters().size());
        assertTrue(batch.deadLetters().get(0).reason().contains("unknown tenant 'res_233'"));
    }

    @Test
    void hierarchy_leaf_must_equal_tenant() {
        var pre = new CdrEventPreprocessor(registryWith("res_999"));
        String bad = """
                [ { "tenant":"res_999", "resellerHierarchy":"telcobright > res_233",
                    "sequenceNo":1, "callId":"c","channelCallUuid":"u",
                    "startTime":"2026-06-17 13:34:43","answerTime":"2026-06-17 13:34:54","endTime":"2026-06-17 13:34:56",
                    "durationSec":1.0,"originatingCallingNumber":"a","terminatingCallingNumber":"a",
                    "originatingCalledNumber":"b","terminatingCalledNumber":"b",
                    "inPartnerId":1,"outPartnerId":2,"callRatePerMinBDT":0.1,"inPartnerUom":"BDT",
                    "inPartnerCost":0.0,"packageAmount":0.0 } ]
                """;
        MultiTenantCdrBatch batch = pre.Preprocess(List.of(bad));

        assertTrue(batch.tenants().isEmpty());
        assertEquals(1, batch.deadLetters().size());
        assertTrue(batch.deadLetters().get(0).reason().contains("leaf 'res_233' != tenant 'res_999'"));
    }

    @Test
    void missing_required_field_is_dead_lettered() {
        var pre = new CdrEventPreprocessor(registryWith("res_233"));
        String noDuration = """
                [ { "tenant":"res_233", "resellerHierarchy":"res_233",
                    "sequenceNo":1, "callId":"c","channelCallUuid":"u",
                    "startTime":"2026-06-17 13:34:43","answerTime":"2026-06-17 13:34:54","endTime":"2026-06-17 13:34:56",
                    "originatingCallingNumber":"a","terminatingCallingNumber":"a",
                    "originatingCalledNumber":"b","terminatingCalledNumber":"b",
                    "inPartnerId":1,"outPartnerId":2,"callRatePerMinBDT":0.1,"inPartnerUom":"BDT",
                    "inPartnerCost":0.0,"packageAmount":0.0 } ]
                """;
        MultiTenantCdrBatch batch = pre.Preprocess(List.of(noDuration));
        assertEquals(1, batch.deadLetters().size());
        assertEquals("missing durationSec", batch.deadLetters().get(0).reason());
    }

    @Test
    void undecodable_value_dead_letters_without_poisoning_the_rest() {
        var pre = new CdrEventPreprocessor(registryWith("res_233", "telcobright"));
        MultiTenantCdrBatch batch = pre.Preprocess(List.of("{ this is not json", CALL_2_TIERS));

        assertEquals(2, batch.tenants().size());          // the good call still processed
        assertEquals(1, batch.deadLetters().size());
        assertTrue(batch.deadLetters().get(0).reason().startsWith("decode failed"));
    }

    @Test
    void same_tenant_across_calls_is_grouped_into_one_entry() {
        var pre = new CdrEventPreprocessor(registryWith("res_233", "telcobright"));
        MultiTenantCdrBatch batch = pre.Preprocess(List.of(CALL_2_TIERS, CALL_2_TIERS));

        assertEquals(2, batch.tenants().size());          // still just res_233 + telcobright
        for (PerTenantCdrs t : batch.tenants())
            assertEquals(2, t.cdrs().size(), t.tenant() + " should hold both calls' tiers");
        assertEquals(Set.of("res_233", "telcobright"),
                Set.of(batch.tenants().get(0).tenant(), batch.tenants().get(1).tenant()));
    }

    @Test
    void last_hierarchy_node_trims_and_handles_trailing_separators() {
        assertEquals("res_233", CdrEventPreprocessor.LastHierarchyNode("telcobright > res_233"));
        assertEquals("telcobright", CdrEventPreprocessor.LastHierarchyNode("telcobright"));
        assertEquals("res_233", CdrEventPreprocessor.LastHierarchyNode("telcobright > res_233 > "));
        assertEquals("", CdrEventPreprocessor.LastHierarchyNode(">"));
    }
}
