package com.telcobright.billing.ingest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.telcobright.billing.ingest.dto.CdrEvent;
import com.telcobright.billing.ingest.dto.RatedCdrEnvelope;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.tenantconfigsync.api.ITenantRegistry;
import com.telcobright.billing.tenantconfigsync.model.Tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The PREPROCESSOR — the pure, unit-testable piece this task adds (contract §1, §3). It turns a poll-batch of
 * Kafka {@code cdr} record values into a {@link MultiTenantCdrBatch}: <b>decode → validate → map
 * {@code CdrEvent}→{@code cdr} → group by tenant → attach each tenant's registry {@link Tenant} context</b>.
 * No IO: it only reads the in-memory {@link ITenantRegistry} snapshot, so it is fully unit-testable.
 *
 * <p><b>Two wire shapes are accepted</b>, distinguished by the value's first JSON token:
 * <ul>
 *   <li><b>array</b> — Contract A ({@code CdrEvent[]}, all tiers of one call in one message; the PROPOSED
 *       shape of {@code docs/cdr-kafka-ingest-contract.md} §2);</li>
 *   <li><b>object</b> — the format routesphere's Kafka CDR sink ACTUALLY emits (observed live 2026-07-16):
 *       {@code {"sequenceNo":N,"cdr":{...}}}, ONE tenant leg per message, adapted via
 *       {@link RatedCdrEnvelope#ToCdrEvent()} onto the same validate/map path.</li>
 * </ul>
 *
 * <p>Bad/unmappable records are routed to the dead-letter list (contract §3.5, §6) rather than poisoning the
 * batch. The pipeline RE-RATES on the actual duration (contract §5): the amounts carried in the event
 * ({@code callRatePerMinBDT}, {@code inPartnerCost}) are routesphere's admission RESERVATION estimates and are
 * mapped through for reference/reconciliation only — they are NOT used as the charge.
 *
 * <p><b>PROPOSED wire contract (§8/§9): flagged, not guessed.</b> The mappings the architect must still ratify
 * are marked {@code // PROPOSED} inline: {@code supplierCost → OutPartnerCost}; {@code isPrepaid} 1=prepaid /
 * 2=postpaid; {@code packageAmount} as its own {@code cdr} column. Required-field validation follows §2's ✓
 * columns — except {@code answerTime}, which the live sink omits on unanswered legs (FAILED/CANCELLED calls
 * are still billing records, charged zero; the rater falls back to startTime).
 */
public final class CdrEventPreprocessor {

    // case-insensitive, ignore-unknown, JSR-310 dates — mirrors ProcessCdrBatchHandler.CdrJson.
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** resellerHierarchy node separator (" > " on the wire; split tolerant of surrounding spaces). */
    private static final String HIERARCHY_SEPARATOR = ">";

    private final ITenantRegistry registry;

    public CdrEventPreprocessor(ITenantRegistry registry) {
        this.registry = registry;
    }

    /**
     * Preprocess ONE poll-batch. Each element of {@code recordValues} is a Kafka record's value: a JSON array
     * of {@link CdrEvent} (all tiers of one call). Returns the tenants grouped for the cross-schema write plus
     * the dead-lettered records. Grouping preserves first-seen tenant order; within a tenant, cdr order follows
     * the poll order.
     */
    public MultiTenantCdrBatch Preprocess(List<String> recordValues) {
        Map<String, List<cdr>> byTenant = new LinkedHashMap<>();
        List<DeadLetteredCdr> dead = new ArrayList<>();

        for (String value : recordValues) {
            List<CdrEvent> events;
            try {
                events = Decode(value);
            } catch (Exception e) {
                dead.add(new DeadLetteredCdr(value, "decode failed: " + e.getMessage()));
                continue;
            }
            for (CdrEvent ev : events) {
                String reason = Validate(ev);
                if (reason != null) {
                    dead.add(new DeadLetteredCdr(Describe(ev), reason));
                    continue;
                }
                byTenant.computeIfAbsent(ev.tenant, k -> new ArrayList<>()).add(Map(ev));
            }
        }

        List<PerTenantCdrs> tenants = new ArrayList<>(byTenant.size());
        for (Map.Entry<String, List<cdr>> e : byTenant.entrySet()) {
            // Validate() already proved the tenant resolves, so this is non-null.
            Tenant context = registry.FindByDbName(e.getKey());
            tenants.add(new PerTenantCdrs(e.getKey(), context, e.getValue()));
        }
        return new MultiTenantCdrBatch(tenants, dead);
    }

    /**
     * Decode ONE record value by its first JSON token: {@code [} = Contract A ({@code CdrEvent[]});
     * {@code {} = the live sink's per-leg envelope ({@code {"sequenceNo":N,"cdr":{...}}}), adapted onto
     * {@link CdrEvent}. Throws on anything else (the caller dead-letters it).
     */
    private static List<CdrEvent> Decode(String value) throws Exception {
        String trimmed = value.stripLeading();
        if (trimmed.startsWith("[")) {
            return JSON.readValue(value,
                    JSON.getTypeFactory().constructCollectionType(List.class, CdrEvent.class));
        }
        RatedCdrEnvelope envelope = JSON.readValue(value, RatedCdrEnvelope.class);
        if (envelope == null || envelope.cdr == null)
            throw new IllegalArgumentException("object record has no 'cdr' payload");
        return List.of(envelope.ToCdrEvent());
    }

    /** Returns null when the event is valid, else a short reason string (goes to the dead-letter row). */
    private String Validate(CdrEvent e) {
        if (Blank(e.tenant)) return "missing tenant";
        if (Blank(e.resellerHierarchy)) return "missing resellerHierarchy";
        if (e.sequenceNo == null) return "missing sequenceNo";
        if (Blank(e.callId)) return "missing callId";
        if (Blank(e.channelCallUuid)) return "missing channelCallUuid";
        if (e.startTime == null) return "missing startTime";
        // answerTime is OPTIONAL: the live sink omits it on unanswered legs (FAILED/CANCELLED calls are
        // still billing records, charged zero; the rater falls back to startTime).
        if (e.endTime == null) return "missing endTime";
        if (e.durationSec == null) return "missing durationSec";
        if (Blank(e.originatingCallingNumber)) return "missing originatingCallingNumber";
        if (Blank(e.terminatingCallingNumber)) return "missing terminatingCallingNumber";
        if (Blank(e.originatingCalledNumber)) return "missing originatingCalledNumber";
        if (Blank(e.terminatingCalledNumber)) return "missing terminatingCalledNumber";
        if (e.inPartnerId == null) return "missing inPartnerId";
        if (e.outPartnerId == null) return "missing outPartnerId";
        if (e.callRatePerMinBDT == null) return "missing callRatePerMinBDT";
        if (Blank(e.inPartnerUom)) return "missing inPartnerUom";
        if (e.inPartnerCost == null) return "missing inPartnerCost";
        if (e.packageAmount == null) return "missing packageAmount";

        String leaf = LastHierarchyNode(e.resellerHierarchy);
        if (!e.tenant.equals(leaf))
            return "resellerHierarchy leaf '" + leaf + "' != tenant '" + e.tenant + "'";
        if (registry.FindByDbName(e.tenant) == null)
            return "unknown tenant '" + e.tenant + "'";
        return null;
    }

    /** Map one validated {@link CdrEvent} onto the engine {@code cdr} (contract §2 / sample B). */
    private static cdr Map(CdrEvent e) {
        cdr c = new cdr();
        c.SequenceNumber = e.sequenceNo;                 // idempotency key within the schema
        c.UniqueBillId = e.callId;
        c.ChannelCallUuid = e.channelCallUuid;           // NEW col
        c.ResellerHierarchy = e.resellerHierarchy;       // NEW col
        // Provenance: the live cdr/cdrerror tables keep the legacy FileName NOT NULL (file mediation put the
        // source CSV name there); Kafka-ingested records carry the topic marker instead.
        c.FileName = "kafka:cdr";
        c.StartTime = e.startTime;
        c.AnswerTime = e.answerTime;
        c.EndTime = e.endTime;
        // Live schema: SignalingStartTime NOT NULL and STRICT_TRANS_TABLES rejects the year-1 sentinel the
        // model defaults to; the leg's signaling start is its startTime (routesphere emits no separate value).
        c.SignalingStartTime = e.startTime;
        c.DurationSec = e.durationSec;                   // the pipeline RE-RATES on this
        c.OriginatingCallingNumber = e.originatingCallingNumber;
        c.TerminatingCallingNumber = e.terminatingCallingNumber;
        c.OriginatingCalledNumber = e.originatingCalledNumber;
        c.TerminatingCalledNumber = e.terminatingCalledNumber;
        c.OriginatingIP = e.callerIp;
        c.TerminatingIP = e.receiverIp;
        c.HangupCause = e.hangupCause;                   // NEW col
        c.Codec = e.channelReadCodecName;
        c.PDD = e.pdd;
        c.InPartnerId = e.inPartnerId;
        c.OutPartnerId = e.outPartnerId;
        c.PrePaid = e.isPrepaid;                         // PROPOSED: 1=prepaid, 2=postpaid (contract §8.3)
        c.MatchedPrefixCustomer = e.matchPrefixCustomer;
        c.MatchedPrefixSupplier = e.supplierPrefix;
        c.AnsIdTerm = e.ansIdTerm;
        c.AnsPrefixTerm = e.ansPrefixTerm;
        c.AnsIdOrig = e.ansIdOrig;
        c.AnsPrefixOrig = e.ansPrefixOrig;
        c.CustomerRate = e.callRatePerMinBDT;            // REFERENCE (admission); re-rated on DurationSec
        c.InPartnerUom = e.inPartnerUom;                 // NEW col
        c.IdPackageAccount = e.idPackageAccount;         // NEW col
        c.InPartnerCost = e.inPartnerCost;               // reference (admission estimate)
        c.PackageAmount = e.packageAmount;               // NEW col
        c.OutPartnerCost = e.supplierCost;               // PROPOSED target (contract §8.3)
        c.CostIcxIn = e.costIcxIn;
        c.CostAnsIn = e.costAnsIn;
        c.RevenueAnsOut = e.revenueAnsOut;
        c.RevenueIgwOut = e.revenueIgwOut;
        return c;
    }

    /** Last node of an {@code admin > … > self} hierarchy, trimmed; "" when the string has no node. */
    static String LastHierarchyNode(String hierarchy) {
        String[] parts = hierarchy.split(HIERARCHY_SEPARATOR);
        for (int i = parts.length - 1; i >= 0; i--) {
            String node = parts[i].trim();
            if (!node.isEmpty()) return node;
        }
        return "";
    }

    private static boolean Blank(String s) {
        return s == null || s.isBlank();
    }

    /** Best-effort payload for a dead-letter row: the event re-serialised, or a terse fallback. */
    private static String Describe(CdrEvent e) {
        try {
            return JSON.writeValueAsString(e);
        } catch (Exception ex) {
            return "{tenant=" + e.tenant + ", sequenceNo=" + e.sequenceNo + "}";
        }
    }
}
