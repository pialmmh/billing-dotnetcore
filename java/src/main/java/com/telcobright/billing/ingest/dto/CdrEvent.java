package com.telcobright.billing.ingest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The Kafka wire DTO for ONE per-tier rated record on the {@code cdr_rated} topic — Contract A of
 * {@code docs/cdr-kafka-ingest-contract.md} (§2). routesphere emits, per call, a JSON <b>array</b> of these
 * (one element per reseller tier); {@link com.telcobright.billing.ingest.CdrEventPreprocessor} decodes,
 * validates, and maps each element onto the engine {@code cdr}.
 *
 * <p>Field names match the wire JSON verbatim (camelCase); the mapper is case-insensitive. The three datetimes
 * arrive as {@code "yyyy-MM-dd HH:mm:ss"} (a space, not the ISO 'T'), so they carry an explicit
 * {@link JsonFormat} pattern.
 *
 * <p><b>PROPOSED — the wire contract is not yet ratified by the architect (contract §8/§9).</b> A few field
 * mappings are flagged in the preprocessor; do not treat this shape as final until routesphere agrees.
 */
public class CdrEvent {
    /** Target schema (routing). Must equal the last node of {@link #resellerHierarchy}. */
    public String tenant;

    /** {@code admin > … > self}, " > "-separated. Leaf == {@link #tenant}. */
    public String resellerHierarchy;

    /** Idempotency key within a schema → {@code cdr.SequenceNumber}. Boxed so "missing" is detectable. */
    public Long sequenceNo;

    /** Call correlation across schemas → {@code cdr.UniqueBillId}. */
    public String callId;

    /** Kafka key + {@code cdr.ChannelCallUuid}. */
    public String channelCallUuid;

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
    public Integer isPrepaid;    // → PrePaid (1=prepaid, 2=postpaid — PROPOSED, contract §8.3)

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
    /** Supplier cost → {@code cdr.OutPartnerCost} (PROPOSED target, contract §8.3). */
    public BigDecimal supplierCost;

    public BigDecimal costIcxIn;     // → CostIcxIn
    public BigDecimal costAnsIn;     // → CostAnsIn
    public BigDecimal revenueAnsOut; // → RevenueAnsOut
    public BigDecimal revenueIgwOut; // → RevenueIgwOut
}
