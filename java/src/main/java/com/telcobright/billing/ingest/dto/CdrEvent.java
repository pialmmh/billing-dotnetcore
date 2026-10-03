package com.telcobright.billing.ingest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The Kafka wire DTO for ONE tier's record of a call on the {@code cdr} topic — Contract A of
 * {@code docs/cdr-kafka-ingest-contract.md} (§2), <b>ratified</b> in routesphere
 * {@code docs/architecture/ad-is-a-call.md} §4. The producer sends, per call, a JSON <b>array</b> of these
 * (one element per tier, the leaf first); {@link com.telcobright.billing.ingest.CdrEventPreprocessor} decodes,
 * validates, and maps each element onto the engine {@code cdr}. Calls and ad views use the same record.
 *
 * <p>Field names match the wire JSON verbatim (camelCase); the mapper is case-insensitive. The three datetimes
 * arrive as {@code "yyyy-MM-dd HH:mm:ss"} (a space, not the ISO 'T') — the wall clock of the root tenant's zone —
 * so they carry an explicit {@link JsonFormat} pattern. {@link #answerTime} may be null: never answered (a call)
 * or never shown (an ad view); the record is still valid.
 *
 * <p>The wire's own JSON schema (the producer is tested against it) is ad-sphere's
 * {@code docs/ad-as-call/contract/cdr-event.schema.json}. What it makes optional is optional here.
 */
public class CdrEvent {
    /** Target schema (routing). Must equal the last node of {@link #resellerHierarchy}. */
    public String tenant;

    /** {@code admin > … > self}, " > "-separated. Leaf == {@link #tenant}. */
    public String resellerHierarchy;

    /** The producer's own running number → {@code cdr.SequenceNumber}: order and diagnosis only (it is not unique
     * across producers or restarts — the idempotency key is {@link #channelCallUuid}). Boxed so "missing" is detectable. */
    public Long sequenceNo;

    /** Call correlation across schemas → {@code cdr.UniqueBillId}. */
    public String callId;

    /** Kafka key → {@code cdr.ChannelCallUuid}. With {@link #tenant} it is the idempotency key: one record per
     * call per tier. */
    public String channelCallUuid;

    /** Absent or 0: billing detects the service group (a call). 30: an ad view — taken as given, never detected
     * → {@code cdr.ServiceGroup}. */
    public Integer serviceGroup;

    /** SIP Call-ID header (the live call feed's field, not on the ratified wire) → {@code cdr.AdditionalMetaData}
     * when {@link #additionalMetaData} is not sent. */
    public String variableSipCallId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime startTime;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime answerTime;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime endTime;

    /** Actual (fractional-second) duration — the pipeline RE-RATES on this (contract §5). */
    public BigDecimal durationSec;

    public String originatingCallingNumber;
    public String terminatingCallingNumber;
    public String originatingCalledNumber;
    public String terminatingCalledNumber;

    public String callerIp;      // → OriginatingIP
    public String receiverIp;    // → TerminatingIP
    public String hangupCause;
    public String channelReadCodecName;   // → Codec
    public Float pdd;

    public Integer inPartnerId;
    public Integer outPartnerId;
    public Integer isPrepaid;    // → PrePaid (1 = prepaid, 2 = postpaid, 0 = unknown)

    /** Optional → {@code cdr.IncomingRoute} / {@code cdr.OutgoingRoute}. A producer that sends none (the live
     * call feed) gets the peer addresses there, as before. */
    public String incomingRoute;
    public String outgoingRoute;

    public String matchPrefixCustomer;   // → MatchedPrefixCustomer
    public String supplierPrefix;        // → MatchedPrefixSupplier

    public Integer ansIdTerm;
    public String ansPrefixTerm;
    public Integer ansIdOrig;
    public String ansPrefixOrig;

    /** Admission reservation per-min rate → {@code cdr.CustomerRate} (REFERENCE only; not the final charge). */
    public BigDecimal callRatePerMinBDT;

    /** {@code BDT}=cash, {@code TF_min}=package, {@code OTH_ea}=per-event → {@code cdr.InPartnerUom}. */
    public String inPartnerUom;
    public Long idPackageAccount;

    /** Admission cash estimate → {@code cdr.InPartnerCost} (reference; the pipeline recomputes the charge). */
    public BigDecimal inPartnerCost;
    /** Billed package units → {@code cdr.PackageAmount} (0 when cash). */
    public BigDecimal packageAmount;
    /** Supplier cost → {@code cdr.OutPartnerCost}. */
    public BigDecimal supplierCost;

    public BigDecimal costIcxIn;     // → CostIcxIn
    public BigDecimal costAnsIn;     // → CostAnsIn
    public BigDecimal revenueAnsOut; // → RevenueAnsOut
    public BigDecimal revenueIgwOut; // → RevenueIgwOut

    /** Optional: a string holding ONE JSON object, the application's own facts → {@code cdr.AdditionalMetaData},
     * written character for character as sent. */
    public String additionalMetaData;
}
