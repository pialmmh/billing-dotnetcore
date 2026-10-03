package com.telcobright.billing.ingest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.telcobright.billing.ingest.dto.CdrEvent;
import com.telcobright.billing.ingest.dto.RatedCdrEnvelope;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.servicegroups.SgAdView;
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
 * batch. For a CALL the pipeline RE-RATES on the actual duration (contract §5): the amounts carried in the event
 * ({@code callRatePerMinBDT}, {@code inPartnerCost}) are the switch's admission RESERVATION estimates and are
 * mapped through for reference only. For an AD VIEW ({@code serviceGroup: 30}) they are what the switch's settle
 * step charged — final — and are written as they came.
 *
 * <p><b>The wire is ratified</b> (routesphere {@code docs/architecture/ad-is-a-call.md} §4). What this class
 * REQUIRES of a record is what the wire's schema requires and billing cannot do without: the tenant, the
 * hierarchy, the ids, the start and end times, the duration, the four numbers and the two amounts. A record with
 * no partner, no rate, no unit or no out-partner is VALID here — a view nobody was admitted for carries none of
 * them — and is judged by the service group's checklists, which send it to {@code cdrerror} when it must not
 * reach {@code cdr}. {@code answerTime} may be null: never answered, or never shown.
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
    /** billing.mediation.switch-id — the source NE's {@code ne.idSwitch}, stamped onto every mapped cdr so the
     * summary's {@code tup_switchid} matches legacy. 0 = unset (the Kafka feed carries no switch id). */
    private final int switchId;

    public CdrEventPreprocessor(ITenantRegistry registry) {
        this(registry, 0);
    }

    public CdrEventPreprocessor(ITenantRegistry registry, int switchId) {
        this.registry = registry;
        this.switchId = switchId;
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
                // The live sink omits tenantName but always sends resellerHierarchy; the target schema is its
                // LEAF node (Validate already asserts tenant == leaf). Derive it so live legs route to their
                // schema instead of dead-lettering as "missing tenant".
                if (Blank(ev.tenant) && !Blank(ev.resellerHierarchy))
                    ev.tenant = LastHierarchyNode(ev.resellerHierarchy);
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
        // answerTime is NULLABLE (ratified): null = never answered (a call) or never shown (an ad view). The
        // record is still valid; the live call sink omits the field on unanswered legs.
        if (e.endTime == null) return "missing endTime";
        if (e.durationSec == null) return "missing durationSec";
        if (Blank(e.originatingCallingNumber)) return "missing originatingCallingNumber";
        if (Blank(e.terminatingCallingNumber)) return "missing terminatingCallingNumber";
        if (Blank(e.originatingCalledNumber)) return "missing originatingCalledNumber";
        if (Blank(e.terminatingCalledNumber)) return "missing terminatingCalledNumber";
        // inPartnerId, outPartnerId, callRatePerMinBDT and inPartnerUom are OPTIONAL on the ratified wire: a view
        // (or a call) nobody was admitted for has no rate, no unit and maybe no partner. Such a record is not a
        // dead letter — it is mediated, and the service group's checklist decides between cdr and cdrerror.
        if (e.inPartnerCost == null) return "missing inPartnerCost";
        if (e.packageAmount == null) return "missing packageAmount";
        if (StatesAnUnknownServiceGroup(e))
            return "service group " + e.serviceGroup + " is not known to this billing-core"
                    + " (a record may state " + SgAdView.Id + "; absent or 0 = billing detects the group)";

        String leaf = LastHierarchyNode(e.resellerHierarchy);
        if (!e.tenant.equals(leaf))
            return "resellerHierarchy leaf '" + leaf + "' != tenant '" + e.tenant + "'";
        if (registry.FindByDbName(e.tenant) == null)
            return "unknown tenant '" + e.tenant + "'";
        return null;
    }

    /** Map one validated {@link CdrEvent} onto the engine {@code cdr} (contract §2 / sample B). */
    private cdr Map(CdrEvent e) {
        cdr c = new cdr();
        c.SwitchId = switchId;                           // billing.mediation.switch-id (source NE idSwitch); 0 when unset
        c.SequenceNumber = e.sequenceNo;                 // the producer's running number: order and diagnosis only
        // A STATED group 30 (an ad view) is taken as given. Absent or 0 is left 0 here and DETECTED by the pipeline,
        // as a call always was. No other value reaches this line: Validate refuses it.
        c.ServiceGroup = SgAdView.IsStatedBy(e.serviceGroup) ? SgAdView.Id : 0;
        c.UniqueBillId = e.callId;
        c.ChannelCallUuid = e.channelCallUuid;           // with the tenant: the idempotency key (one record per call per tier)
        c.AdditionalMetaData = MetaDataOf(e);            // the wire's own JSON object, else the live feed's SIP Call-ID
        c.ResellerHierarchy = e.resellerHierarchy;
        // Provenance: the live cdr/cdrerror tables keep the legacy FileName NOT NULL (file mediation put the
        // source CSV name there); Kafka-ingested records carry the topic marker instead.
        c.FileName = "kafka:cdr";
        c.StartTime = e.startTime;
        c.AnswerTime = e.answerTime;
        // ConnectTime = AnswerTime (ops observation 2026-07-25): the moment the call connected IS the answer
        // time. Null on unanswered/failed legs (the live sink omits answerTime there), so the summary's
        // connectedcalls stays 0 for failed calls and 1 for answered — preserving the connected-vs-total split.
        c.ConnectTime = e.answerTime;
        c.EndTime = e.endTime;
        // Live schema: SignalingStartTime NOT NULL and STRICT_TRANS_TABLES rejects the year-1 sentinel the
        // model defaults to; the leg's signaling start is its startTime (routesphere emits no separate value).
        c.SignalingStartTime = e.startTime;
        c.DurationSec = e.durationSec;                   // the pipeline RE-RATES on this
        // ChargingStatus drives the summary's successfulcalls (legacy CdrSummaryFactory: successfulcalls =
        // ChargingStatus; legacy FinalizeEngine sets it from Answered()). On the routesphere feed billsec
        // (-> durationSec) is 0 on unanswered/failed legs, so duration > 0 IS the "answered/charged" signal.
        // Was left null on the Kafka path -> successfulcalls always folded 0 even for fully-billed calls.
        c.ChargingStatus = (e.durationSec != null && e.durationSec.signum() > 0) ? 1 : 0;
        c.OriginatingCallingNumber = e.originatingCallingNumber;
        c.TerminatingCallingNumber = e.terminatingCallingNumber;
        c.OriginatingCalledNumber = e.originatingCalledNumber;
        c.TerminatingCalledNumber = e.terminatingCalledNumber;
        c.OriginatingIP = e.callerIp;
        c.TerminatingIP = e.receiverIp;
        // The wire's own routes win (ratified: incomingRoute / outgoingRoute). A producer that sends none — the
        // live call feed — keeps the peer addresses there: on that IP-trunk topology the routes ARE the peer IPs
        // (ops observation 2026-07-25): the call comes IN from the receiver side and goes OUT toward the caller
        // side, so IncomingRoute = receiverIp and OutgoingRoute = callerIp.
        c.IncomingRoute = !Blank(e.incomingRoute) ? e.incomingRoute : e.receiverIp;
        c.OutgoingRoute = !Blank(e.outgoingRoute) ? e.outgoingRoute : e.callerIp;
        c.HangupCause = e.hangupCause;                   // ratified column (written where the table has it)
        c.AreaCodeOrLata = e.hangupCause;                // and where the call lane has always kept it
        c.Codec = e.channelReadCodecName;
        c.PDD = e.pdd;
        c.InPartnerId = e.inPartnerId;
        c.OutPartnerId = e.outPartnerId;
        c.PrePaid = e.isPrepaid;                         // 1 = prepaid, 2 = postpaid, 0 = unknown
        c.MatchedPrefixCustomer = e.matchPrefixCustomer;
        c.MatchedPrefixSupplier = e.supplierPrefix;
        c.AnsIdTerm = e.ansIdTerm;
        c.AnsPrefixTerm = e.ansPrefixTerm;
        c.AnsIdOrig = e.ansIdOrig;
        c.AnsPrefixOrig = e.ansPrefixOrig;
        c.CustomerRate = e.callRatePerMinBDT;            // REFERENCE (admission); re-rated on DurationSec
        c.InPartnerUom = e.inPartnerUom;
        c.IdPackageAccount = e.idPackageAccount;
        c.InPartnerCost = e.inPartnerCost;               // a call: reference (admission estimate); re-rated
        c.PackageAmount = e.packageAmount;
        c.OutPartnerCost = e.supplierCost;
        c.CostIcxIn = e.costIcxIn;
        c.CostAnsIn = e.costAnsIn;
        c.RevenueAnsOut = e.revenueAnsOut;
        c.RevenueIgwOut = e.revenueIgwOut;
        return c;
    }

    /** A record that STATES a group is never re-classified by a guess (architect's ruling 2026-10-04). Billing has
     * one group it takes as given, 30; a record that states any other non-zero number is refused in words — it is
     * not handed to the detectors, which would silently bill it as whatever they make of it. */
    private static boolean StatesAnUnknownServiceGroup(CdrEvent e) {
        return e.serviceGroup != null && e.serviceGroup != 0 && !SgAdView.IsStatedBy(e.serviceGroup);
    }

    /** {@code cdr.AdditionalMetaData}: the wire's {@code additionalMetaData} (one JSON object, as sent) when the
     * producer sends it; else the live call feed's SIP Call-ID, which has lived in that column since T1. */
    private static String MetaDataOf(CdrEvent e) {
        return !Blank(e.additionalMetaData) ? e.additionalMetaData : e.variableSipCallId;
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
