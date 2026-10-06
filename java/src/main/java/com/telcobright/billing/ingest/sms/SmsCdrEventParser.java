package com.telcobright.billing.ingest.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.sms.SmsOutgoing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parses ONE outgoing-SMS CDR record (routesphere {@code SmsCdrGenerator}'s CDR shape) into the engine {@code cdr}.
 *
 * <pre>
 * {"idCall":"1000000000000000001","terminatingCallingNumber":"8809600000001","terminatingCalledNumber":"8801700000001",
 *  "startTime":"2026-10-05 11:15:51","endTime":"2026-10-05 11:15:51","inPartnerId":301,"outPartnerId":23,
 *  "switchId":1,"sequenceNumber":0,"serviceGroup":1,"originatingCallingNumber":"8809600000001",
 *  "originatingCalledNumber":"8801700000001","durationSec":60,"signalingStartTime":"2026-10-05 11:15:51",
 *  "message":"U3ludGhldGljIHRlc3QgbWVzc2FnZQ==","campaignId":2,"smsCount":1}
 * </pre>
 *
 * <ul>
 * <li>{@code idCall} → {@code UniqueBillId} (verbatim string) AND {@code SequenceNumber} (the legacy SMS biller stores the
 *   routesphere idCall in SequenceNumber). It is the ownership/dedup identity; the event's {@code sequenceNumber}
 *   (always 0) never replaces it.</li>
 * <li>{@code serviceGroup} is IGNORED: the SMS pipeline makes every record SG20.</li>
 * <li>{@code durationSec} — the record's {@code "durationSec"} property — is the ONLY billing duration, parsed and
 *   never assumed or derived: billing units = durationSec / 60 (60/120/180 → 1/2/3). {@code ChargingStatus = 1}
 *   when it is &gt; 0 (legacy decoder rule).</li>
 * <li>{@code message} → {@code AdditionalMetaData} VERBATIM (Base64 is not decoded).</li>
 * <li>{@code startTime} is Asia/Dhaka wall time (as all billing timestamps) and is also the answer time;
 *   {@code ConnectTime} stays NULL, as on legacy SMS rows.</li>
 * <li>{@code campaignId} has no cdr column (legacy did not persist it either) and is not stored.</li>
 * <li>{@code smsCount} is the producer's TotalCall / SuccessfulCall count — NOT a multipart count — and has NO
 *   relationship with {@code durationSec}. It has no billing use and no cdr column: it never affects DurationSec,
 *   the billed quantity, the rate or the amount. It is only type-checked (an integer &gt;= 0) and otherwise ignored;
 *   a malformed value is reported as a {@link Parsed#Warning} and never blocks billing.</li>
 * </ul>
 * A record that cannot be billed safely (bad JSON, no/invalid {@code idCall}, no start time, no duration, no called
 * number) is DEAD-LETTERED — never guessed, never billed.
 */
public final class SmsCdrEventParser {
    private static final ObjectMapper Json = new ObjectMapper();
    private static final DateTimeFormatter SpaceFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * One record's outcome: a cdr, or the dead-letter reason. {@code SmsCount} is the validated value as sent (null
     * when absent or malformed — informational only, never used for billing); {@code Warning} reports a field that was
     * ignored (e.g. a malformed smsCount) on a record that is still billed.
     */
    public record Parsed(cdr Cdr, String DeadLetterReason, Integer SmsCount, String Warning) {
        public boolean Ok() { return Cdr != null; }
    }

    private final int _fallbackSwitchId;

    /** @param fallbackSwitchId used only when the record carries no {@code switchId} (billing.mediation.switch-id). */
    public SmsCdrEventParser(int fallbackSwitchId) {
        _fallbackSwitchId = fallbackSwitchId;
    }

    public Parsed Parse(String json) {
        JsonNode n;
        try {
            n = json == null ? null : Json.readTree(json);
        } catch (Exception ex) {
            return Dead("invalid JSON: " + ex.getMessage());
        }
        if (n == null || !n.isObject()) return Dead("record is not a JSON object");

        String idCall = Text(n, "idCall");
        if (idCall == null || idCall.isBlank()) return Dead("missing idCall");
        idCall = idCall.trim();
        long seq;
        try {
            seq = Long.parseLong(idCall);
        } catch (NumberFormatException ex) {
            return Dead("idCall is not numeric: '" + idCall + "'");
        }
        if (seq <= 0) return Dead("idCall must be > 0: " + idCall);

        LocalDateTime start;
        try {
            start = Time(n, "startTime");
        } catch (DateTimeParseException ex) {
            return Dead("startTime unparseable: " + ex.getParsedString());
        }
        if (start == null) return Dead("missing startTime");
        LocalDateTime end, signaling;
        try {
            end = Time(n, "endTime");
            signaling = Time(n, "signalingStartTime");
        } catch (DateTimeParseException ex) {
            return Dead("time unparseable: " + ex.getParsedString());
        }

        BigDecimal duration = Decimal(n, "durationSec");
        if (duration == null) return Dead("missing or non-numeric durationSec");

        // smsCount (TotalCall / SuccessfulCall) is unrelated to durationSec and has no billing use: type-check it,
        // keep it informational, and NEVER let it change or block the bill.
        Integer smsCount = null;
        String warning = null;
        JsonNode countNode = n.get("smsCount");
        if (countNode != null && !countNode.isNull()) {
            smsCount = NonNegativeInt(countNode);
            if (smsCount == null)
                warning = "smsCount ignored: not an integer >= 0: '" + countNode.asText() + "'";
        }

        String termCalling = Text(n, "terminatingCallingNumber");
        String termCalled = Text(n, "terminatingCalledNumber");
        String origCalling = Text(n, "originatingCallingNumber");
        String origCalled = Text(n, "originatingCalledNumber");
        // legacy decoder: an empty originating called number takes the terminating one; routesphere writes the
        // terminating calling number as the originating one, so the same fallback is applied on the calling side.
        if (Blank(origCalled)) origCalled = termCalled;
        if (Blank(origCalling)) origCalling = termCalling;
        if (Blank(origCalled)) return Dead("missing called number");

        cdr c = new cdr();
        c.UniqueBillId = idCall;
        c.SequenceNumber = seq;
        c.FileName = SmsOutgoing.FileNameMarker;
        Integer switchId = Int(n, "switchId");
        c.SwitchId = switchId != null ? switchId : _fallbackSwitchId;
        c.TerminatingCallingNumber = termCalling;
        c.TerminatingCalledNumber = termCalled;
        c.OriginatingCallingNumber = origCalling;
        c.OriginatingCalledNumber = origCalled;
        c.StartTime = start;
        c.AnswerTime = start;
        c.EndTime = end != null ? end : start;
        c.SignalingStartTime = signaling != null ? signaling : start;
        c.InPartnerId = Int(n, "inPartnerId");
        c.OutPartnerId = Int(n, "outPartnerId");
        c.DurationSec = duration;
        c.ChargingStatus = duration.signum() > 0 ? 1 : 0;
        c.AdditionalMetaData = Text(n, "message");
        c.ValidFlag = 1;
        c.PartialFlag = 0;
        // legacy decode-time zeros on every legacy SMS row (cdr and cdrerror alike)
        c.InPartnerCost = BigDecimal.ZERO;
        c.OutPartnerCost = BigDecimal.ZERO;
        c.CostAnsIn = BigDecimal.ZERO;
        c.CostIcxIn = BigDecimal.ZERO;
        c.Tax1 = BigDecimal.ZERO;
        c.IgwRevenueIn = BigDecimal.ZERO;
        c.RevenueAnsOut = BigDecimal.ZERO;
        c.RevenueIgwOut = BigDecimal.ZERO;
        c.RevenueIcxOut = BigDecimal.ZERO;
        c.Tax2 = BigDecimal.ZERO;
        c.XAmount = BigDecimal.ZERO;
        c.YAmount = BigDecimal.ZERO;
        return new Parsed(c, null, smsCount, warning);
    }

    private static Parsed Dead(String reason) {
        return new Parsed(null, reason, null, null);
    }

    /** An integer >= 0 (JSON integer, or a numeric string); null for anything else — no rounding, no overflow. */
    private static Integer NonNegativeInt(JsonNode v) {
        long value;
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            value = v.longValue();
        } else if (v.isTextual()) {
            try { value = Long.parseLong(v.asText().trim()); } catch (NumberFormatException ex) { return null; }
        } else {
            return null;
        }
        return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : null;
    }

    private static String Text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        return v.isValueNode() ? v.asText() : null;
    }

    private static Integer Int(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.intValue();
        if (v.isTextual()) {
            try { return Integer.parseInt(v.asText().trim()); } catch (NumberFormatException ex) { return null; }
        }
        return null;
    }

    private static BigDecimal Decimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) {
            try { return new BigDecimal(v.asText().trim()); } catch (NumberFormatException ex) { return null; }
        }
        return null;
    }

    private static LocalDateTime Time(JsonNode n, String field) {
        String s = Text(n, field);
        if (Blank(s)) return null;
        s = s.trim();
        return s.indexOf('T') > 0 ? LocalDateTime.parse(s) : LocalDateTime.parse(s, SpaceFormat);
    }

    private static boolean Blank(String s) {
        return s == null || s.isBlank();
    }
}
