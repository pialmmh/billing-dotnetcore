package com.telcobright.billing.testsupport;

import com.telcobright.billing.ingest.CdrEventPreprocessor;
import com.telcobright.billing.ingest.MultiTenantCdrBatch;
import com.telcobright.billing.ingest.PerTenantCdrs;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ad view on the ratified wire, as the tests feed it: the brief's own sample (one view, two tiers) and a view
 * nobody was admitted for, in the shape seed-callflow's {@code CdrAssembler} sends them. {@link #cdrsOf} maps a
 * wire value with the REAL preprocessor, so a mediation test starts from what the ingest really hands the pipeline.
 */
public final class AdViewSamples {
    private AdViewSamples() {}

    /** The ad session id of {@link #ONE_VIEW_TWO_TIERS}: its {@code callId} and its {@code channelCallUuid}. */
    public static final String VIEW_ID = "2f6c1c1e-7a52-4d0b-9c7e-51c0a4b7e9aa";

    /** The brief's §4 sample, character for character: one view, two tiers, the leaf first — the reseller
     * {@code res_44}'s client pays 0.50, the reseller pays the operator 0.40. */
    public static final String ONE_VIEW_TWO_TIERS = """
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

    /** The ad session id of {@link #A_REFUSED_VIEW}. */
    public static final String REFUSED_VIEW_ID = "9d1e0f0a-0000-4000-8000-00000000r001";

    /** A view refused before anyone was admitted, as seed-callflow's {@code CdrAssembler.unadmittedRecord} sends it:
     * one record on the entry tenant, {@code answerTime} null, no rate, no unit, no prefix, no account, no out-partner. */
    public static final String A_REFUSED_VIEW = """
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

    /** A registry in which exactly the named tenants (schemas) are loaded. */
    public static ITenantRegistry registryWith(String... dbNames) {
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

    /** The tenant's cdrs of the given wire values, as the real preprocessor maps them (tiers {@code btcl} and
     * {@code res_44} are loaded). Fresh objects at every call: a test may change them. */
    public static List<cdr> cdrsOf(String tenant, String... wireValues) {
        MultiTenantCdrBatch batch = new CdrEventPreprocessor(registryWith("btcl", "res_44")).Preprocess(List.of(wireValues));
        if (!batch.deadLetters().isEmpty())
            throw new AssertionError("the sample was refused: " + batch.deadLetters());
        for (PerTenantCdrs slice : batch.tenants())
            if (slice.tenant().equals(tenant)) return slice.cdrs();
        throw new AssertionError("the sample holds no record for tenant " + tenant);
    }
}
