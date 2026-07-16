package com.telcobright.billing.ingest.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * The Kafka wire DTO for the format routesphere's Kafka CDR sink ACTUALLY emits on the {@code cdr} topic
 * (observed live 2026-07-16; routesphere commit "independent CSV + Kafka CDR sinks"): ONE message per tenant
 * leg, shaped {@code {"sequenceNo":N,"cdr":{...}}}, key = {@code cdr.callId}. The same call therefore arrives
 * as several messages (one per node of {@code resellerHierarchy}), unlike Contract A's single
 * {@code CdrEvent[]} array carrying all tiers.
 *
 * <p>{@link #ToCdrEvent()} adapts a leg onto the Contract-A {@link CdrEvent} so the rest of the preprocessor
 * (validate → map → group) is shared. Live differences the adapter absorbs:
 * <ul>
 *   <li>datetimes are ISO-8601 <b>UTC</b> instants ({@code 2026-07-15T10:15:36.100Z}) plus
 *       {@code startTimeLocal}/{@code endTimeLocal} wall-clock twins. Billing runs on LOCAL wall-clock (the
 *       legacy pipeline rates by local time windows), so the adapter uses the local twins and shifts
 *       {@code answerTime} (which has NO local twin) by the same startTime offset.</li>
 *   <li>{@code billsec} is the billable duration → {@code durationSec} ({@code totalDurationSeconds} includes
 *       ring time).</li>
 *   <li>FAILED/CANCELLED legs omit {@code answerTime}, {@code outPartnerId}, {@code rate}, {@code uom},
 *       admission costs — the adapter defaults the reference amounts to zero ("no admission estimate") and
 *       leaves {@code answerTime} null (unanswered; the rater falls back to startTime).</li>
 * </ul>
 */
public class RatedCdrEnvelope {

    public Long sequenceNo;
    public Leg cdr;

    /** The per-leg payload — field names verbatim from the live wire JSON (mapper is case-insensitive). */
    public static class Leg {
        public String callId;
        public String sessionId;
        public String tenantName;
        public String resellerHierarchy;

        public OffsetDateTime startTime;      // UTC instant
        public OffsetDateTime answerTime;     // UTC instant; null on unanswered legs
        public OffsetDateTime endTime;        // UTC instant
        public LocalDateTime startTimeLocal;  // wall-clock twin of startTime
        public LocalDateTime endTimeLocal;    // wall-clock twin of endTime

        public BigDecimal billsec;            // billable seconds (0 when unanswered)
        public BigDecimal totalDurationSeconds;

        public String callerNumber;
        public String calledNumber;
        public String terminatingCallingNumber;
        public String terminatingCalledNumber;

        public String callerIp;
        public String receiverIp;
        public String hangupCause;
        public String channelReadCodecName;
        public Float pdd;

        public Integer partnerId;             // this leg's (in-)partner
        public Integer outPartnerId;
        public Integer isPrepaid;             // 1=prepaid, 2=postpaid; null on failed legs

        public String matchPrefixCustomer;
        public String supplierPrefix;

        public Integer ansIdTerm;
        public String ansPrefixTerm;
        public Integer ansIdOrig;
        public String ansPrefixOrig;

        public BigDecimal rate;               // admission per-min rate (reference)
        public String uom;                    // BDT / TF_min / OTH_ea
        public Long packageAccountId;

        public BigDecimal inPartnerCost;      // admission estimate (reference)
        public BigDecimal packageAmount;
        public BigDecimal supplierCost;
        public BigDecimal costIcxIn;
        public BigDecimal costAnsIn;
        public BigDecimal revenueAnsOut;
        public BigDecimal revenueIgwOut;
    }

    /** Adapt this live-format leg onto the Contract-A {@link CdrEvent} (shared validate/map path). */
    public CdrEvent ToCdrEvent() {
        Leg leg = this.cdr;
        CdrEvent e = new CdrEvent();
        e.tenant = leg.tenantName;
        e.resellerHierarchy = leg.resellerHierarchy;
        e.sequenceNo = this.sequenceNo;
        e.callId = leg.callId;
        e.channelCallUuid = leg.callId;                  // the sink keys records by callId

        // LOCAL wall-clock: prefer the local twins; answerTime (UTC-only) shifts by the startTime offset.
        Duration utcToLocal = (leg.startTime != null && leg.startTimeLocal != null)
                ? Duration.between(leg.startTime.toLocalDateTime(), leg.startTimeLocal)
                : Duration.ZERO;
        e.startTime = leg.startTimeLocal != null
                ? leg.startTimeLocal
                : (leg.startTime != null ? leg.startTime.toLocalDateTime() : null);
        e.answerTime = leg.answerTime != null ? leg.answerTime.toLocalDateTime().plus(utcToLocal) : null;
        e.endTime = leg.endTimeLocal != null
                ? leg.endTimeLocal
                : (leg.endTime != null ? leg.endTime.toLocalDateTime().plus(utcToLocal) : null);

        e.durationSec = leg.billsec != null ? leg.billsec : BigDecimal.ZERO;   // billable, not ring-inclusive

        e.originatingCallingNumber = leg.callerNumber;
        e.terminatingCallingNumber = leg.terminatingCallingNumber != null ? leg.terminatingCallingNumber : leg.callerNumber;
        e.originatingCalledNumber = leg.calledNumber;
        e.terminatingCalledNumber = leg.terminatingCalledNumber != null ? leg.terminatingCalledNumber : leg.calledNumber;

        e.callerIp = leg.callerIp;
        e.receiverIp = leg.receiverIp;
        e.hangupCause = leg.hangupCause;
        e.channelReadCodecName = leg.channelReadCodecName;
        e.pdd = leg.pdd;

        e.inPartnerId = leg.partnerId;
        e.outPartnerId = leg.outPartnerId != null ? leg.outPartnerId : 0;      // 0 = no egress partner (failed leg)
        e.isPrepaid = leg.isPrepaid;

        e.matchPrefixCustomer = leg.matchPrefixCustomer;
        e.supplierPrefix = leg.supplierPrefix;

        e.ansIdTerm = leg.ansIdTerm;
        e.ansPrefixTerm = leg.ansPrefixTerm;
        e.ansIdOrig = leg.ansIdOrig;
        e.ansPrefixOrig = leg.ansPrefixOrig;

        // reference amounts: failed legs carry none — default to zero ("no admission estimate").
        e.callRatePerMinBDT = leg.rate != null ? leg.rate : BigDecimal.ZERO;
        e.inPartnerUom = leg.uom != null ? leg.uom : "BDT";
        e.idPackageAccount = leg.packageAccountId;
        e.inPartnerCost = leg.inPartnerCost != null ? leg.inPartnerCost : BigDecimal.ZERO;
        e.packageAmount = leg.packageAmount != null ? leg.packageAmount : BigDecimal.ZERO;
        e.supplierCost = leg.supplierCost != null ? leg.supplierCost : BigDecimal.ZERO;
        e.costIcxIn = leg.costIcxIn;
        e.costAnsIn = leg.costAnsIn;
        e.revenueAnsOut = leg.revenueAnsOut;
        e.revenueIgwOut = leg.revenueIgwOut;
        return e;
    }
}
