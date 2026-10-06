package com.telcobright.billing.ingest.sms;

import com.telcobright.billing.testsupport.SmsTestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Phase-1 Kafka SMS CDR contract, pinned against the record shape (synthetic values). */
class SmsCdrEventParserTests {

    private final SmsCdrEventParser parser = new SmsCdrEventParser(0);
    private static final String Json = SmsTestData.SampleJson;

    private static String WithDurationAndCount(int durationSec, String smsCount) {
        String j = Json.replace("\"durationSec\": 60", "\"durationSec\": " + durationSec);
        return smsCount == null ? j.replace(",\n  \"smsCount\": 1", "") : j.replace("\"smsCount\": 1", "\"smsCount\": " + smsCount);
    }

    @Test
    void sample_json_maps_every_field() {
        var p = parser.Parse(Json);
        assertTrue(p.Ok(), () -> "dead-lettered: " + p.DeadLetterReason());
        var c = p.Cdr();
        LocalDateTime t = LocalDateTime.of(2026, 10, 5, 11, 15, 51);

        assertEquals("1000000000000000001", c.UniqueBillId, "idCall -> UniqueBillId verbatim");
        assertEquals(1000000000000000001L, c.SequenceNumber, "idCall -> SequenceNumber (the legacy SMS biller stores idCall there)");
        assertEquals("kafka:sms", c.FileName);
        assertEquals(SmsTestData.NumericSender, c.TerminatingCallingNumber);
        assertEquals(SmsTestData.Called, c.TerminatingCalledNumber);
        assertEquals(SmsTestData.NumericSender, c.OriginatingCallingNumber);
        assertEquals(SmsTestData.Called, c.OriginatingCalledNumber);
        assertEquals(t, c.StartTime);
        assertEquals(t, c.AnswerTime, "the SMS creation time is also its answer time (legacy)");
        assertEquals(t, c.EndTime);
        assertEquals(t, c.SignalingStartTime);
        assertNull(c.ConnectTime, "legacy SMS rows carry no ConnectTime");
        assertEquals(SmsTestData.PartnerA, c.InPartnerId);
        assertEquals(SmsTestData.OutPartner, c.OutPartnerId);
        assertEquals(1, c.SwitchId);
        assertEquals(0, new BigDecimal("60").compareTo(c.DurationSec));
        assertEquals(1, c.ChargingStatus);
        assertEquals(SmsTestData.Message, c.AdditionalMetaData, "message is stored VERBATIM (not decoded)");
        assertEquals(0, c.ServiceGroup, "the event's serviceGroup=1 is ignored; the SMS pipeline sets 20");
        assertEquals(1, c.ValidFlag);
        assertEquals(0, c.PartialFlag);
        assertEquals(0, BigDecimal.ZERO.compareTo(c.OutPartnerCost), "legacy decode-time zero");
        assertNull(c.RoundedDuration, "RoundedDuration stays NULL for SMS");
        assertEquals(1, p.SmsCount());
    }

    @Test
    void sequenceNumber_zero_in_the_event_never_replaces_idCall() {
        assertEquals(1000000000000000001L, parser.Parse(Json).Cdr().SequenceNumber);
    }

    @Test
    void durationSec_is_parsed_not_assumed_and_matching_smsCount_is_accepted() {
        for (int parts = 1; parts <= 3; parts++) {
            var p = parser.Parse(WithDurationAndCount(60 * parts, Integer.toString(parts)));
            assertTrue(p.Ok(), () -> "dead-lettered: " + p.DeadLetterReason());
            assertEquals(0, BigDecimal.valueOf(60L * parts).compareTo(p.Cdr().DurationSec));
            assertEquals(parts, p.SmsCount());
            assertEquals(1, p.Cdr().ChargingStatus);
        }
    }

    @Test
    void smsCount_is_optional() {
        var p = parser.Parse(WithDurationAndCount(120, null));
        assertTrue(p.Ok());
        assertNull(p.SmsCount());
        assertEquals(0, new BigDecimal("120").compareTo(p.Cdr().DurationSec), "durationSec drives the charge");
        var zero = parser.Parse(WithDurationAndCount(0, null)).Cdr();
        assertEquals(0, zero.ChargingStatus, "0 seconds -> not charged (legacy decoder rule)");
    }

    @Test
    void smsCount_contradicting_durationSec_is_dead_lettered_never_guessed() {
        var p = parser.Parse(WithDurationAndCount(60, "3"));     // per-part 60 s but 3 parts claimed
        assertFalse(p.Ok());
        assertTrue(p.DeadLetterReason().contains("does not match smsCount"), p.DeadLetterReason());
        assertFalse(parser.Parse(WithDurationAndCount(180, "2")).Ok());
    }

    @Test
    void invalid_smsCount_is_dead_lettered() {
        assertFalse(parser.Parse(WithDurationAndCount(60, "0")).Ok());
        assertFalse(parser.Parse(WithDurationAndCount(60, "-1")).Ok());
        assertFalse(parser.Parse(WithDurationAndCount(60, "\"two\"")).Ok());
        assertTrue(parser.Parse(WithDurationAndCount(60, "\"1\"")).Ok(), "a numeric string is accepted");
    }

    @Test
    void numeric_idCall_is_accepted_too() {
        var c = parser.Parse(Json.replace("\"1000000000000000001\"", "1000000000000000001")).Cdr();
        assertEquals("1000000000000000001", c.UniqueBillId);
    }

    @Test
    void empty_originating_numbers_fall_back_to_terminating() {
        var json = Json
                .replace("\"originatingCalledNumber\": \"" + SmsTestData.Called + "\"", "\"originatingCalledNumber\": \"\"")
                .replace("\"originatingCallingNumber\": \"" + SmsTestData.NumericSender + "\"", "\"originatingCallingNumber\": \"\"");
        var c = parser.Parse(json).Cdr();
        assertEquals(SmsTestData.Called, c.OriginatingCalledNumber);
        assertEquals(SmsTestData.NumericSender, c.OriginatingCallingNumber);
    }

    @Test
    void unbillable_records_are_dead_lettered_never_guessed() {
        assertFalse(parser.Parse("not json").Ok());
        assertFalse(parser.Parse("[1,2]").Ok());
        assertFalse(parser.Parse(Json.replace("\"idCall\": \"1000000000000000001\",", "")).Ok(), "no idCall");
        assertFalse(parser.Parse(Json.replace("\"1000000000000000001\"", "\"abc\"")).Ok(), "non-numeric idCall");
        assertFalse(parser.Parse(Json.replace("\"startTime\": \"2026-10-05 11:15:51\",", "")).Ok(), "no startTime");
        assertFalse(parser.Parse(Json.replace("\"startTime\": \"2026-10-05 11:15:51\"", "\"startTime\": \"05/10/2026\"")).Ok());
        assertFalse(parser.Parse(Json.replace("\"durationSec\": 60,", "")).Ok(), "no duration");
        var noCalled = Json
                .replace("\"terminatingCalledNumber\": \"" + SmsTestData.Called + "\",", "")
                .replace("\"originatingCalledNumber\": \"" + SmsTestData.Called + "\",", "");
        assertFalse(parser.Parse(noCalled).Ok(), "no called number");
    }
}
