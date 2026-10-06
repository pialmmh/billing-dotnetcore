package com.telcobright.billing.mediation.rating;

import com.telcobright.billing.mediation.cdr.CdrPipeline;
import com.telcobright.billing.mediation.engine.models.cdr;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.mediation.model.Partner;
import com.telcobright.billing.mediation.rating.ratecaching.PrefixMatcher;
import com.telcobright.billing.mediation.rating.ratecaching.RateCache;
import com.telcobright.billing.mediation.servicegroups.ServiceGroupDetection;
import com.telcobright.billing.mediation.sms.SmsBillingRuleCatalog;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Voice/PBX must be untouched by the outgoing-SMS work: called-number-only matching through the existing
 * PrefixMatcher, the existing SG detection, the sanitized voice cache keys, and no SMS behaviour on Default().
 */
class SmsVoiceIsolationRegressionTests {
    private static final char US = '\u001F';
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 10, 5, 11, 15, 51);

    private static TestData.Ra Row(String prefix, String amount) {
        var ra = TestData.Ra(0, amount);
        ra.build().Prefix = prefix;
        return ra;
    }

    @Test
    void the_voice_cache_still_keys_the_sanitized_prefix_and_keeps_the_raw_one_aside() {
        var f = TestData.fixture();
        var t = f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, Row("BRAND" + US + "8801", "0.55"), Row("8801", "0.33"));
        var day = RateCache.DayRange(LocalDate.of(2026, 10, 5));
        var dict = f.rateCache().GetRateDictsByDay(day).get(f.tupsForDay(day).get(0));
        assertTrue(dict.containsKey("BRAND8801"), "voice key = SanitizePrefix(raw) — unchanged");
        assertTrue(dict.containsKey("8801"));
        assertFalse(dict.containsKey("BRAND" + US + "8801"), "the raw composite never becomes a voice key");
        var composite = dict.get("BRAND8801").get(0);
        assertEquals("BRAND8801", composite.Prefix);
        assertEquals("BRAND" + US + "8801", composite.RawPrefix);
        assertNotNull(t);
    }

    @Test
    void voice_prefix_matching_on_the_called_number_is_unchanged_by_composite_rows() {
        var plain = TestData.fixture();
        plain.tup(10, AssignmentDirection.Customer.value, 5, null, 0, Row("8801", "0.33"));
        var mixed = TestData.fixture();
        mixed.tup(10, AssignmentDirection.Customer.value, 5, null, 0, Row("8801", "0.33"), Row("BRAND" + US + "8801", "0.55"));
        var day = RateCache.DayRange(LocalDate.of(2026, 10, 5));
        for (var f : new TestData.Fixture[] {plain, mixed}) {
            var r = new PrefixMatcher(f.rateCache(), "8801712345678", 1, 1, f.tupsForDay(day), WHEN).MatchPrefix();
            assertEquals("8801", r.Prefix);
            assertEquals(0, new BigDecimal("0.33").compareTo(r.rateamount));
        }
    }

    @Test
    void default_detection_still_classifies_voice_by_partner_type_and_destination() {
        var d = ServiceGroupDetection.Default();
        assertEquals(10, d.Detect(Call(5, "8801712345678"), Map.of(5, new Partner(5, null, 3))).ServiceGroupId());
        assertEquals(11, d.Detect(Call(5, "8801712345678"), Map.of(5, new Partner(5, null, 2))).ServiceGroupId());
        assertEquals(15, d.Detect(Call(5, "0085212345678"), Map.of(5, new Partner(5, null, 3))).ServiceGroupId());
        assertEquals(20, ServiceGroupDetection.SmsOutgoing().Detect(Call(5, "8801712345678"), Map.of()).ServiceGroupId());
    }

    @Test
    void the_default_pipeline_and_rater_carry_no_sms_behaviour() {
        assertFalse(CdrPipeline.Default().IsSmsOutgoing());
        assertTrue(CdrPipeline.SmsOutgoing(SmsBillingRuleCatalog.Empty(), new com.telcobright.billing.testsupport.SmsTestData.InMemoryAccounting(null)).IsSmsOutgoing());
        // the voice-only operations are refused on the SMS rater, so it can never be wired into admission/finalize
        var sms = BasicCharge.SmsOutgoing(SmsBillingRuleCatalog.Empty());
        var f = TestData.fixture();
        assertThrows(UnsupportedOperationException.class, () -> sms.MatchCustomerRate(Call(5, "8801"), f.mediation(), Map.of()));
        assertThrows(UnsupportedOperationException.class,
                () -> sms.Compute(Call(5, "8801"), AssignmentDirection.Customer, f.mediation(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BasicCharge.SmsOutgoing(null));
    }

    private static cdr Call(int inPartner, String called) {
        cdr c = new cdr();
        c.InPartnerId = inPartner; c.OutPartnerId = 9;
        c.OriginatingCalledNumber = called; c.TerminatingCalledNumber = called;
        c.OriginatingCallingNumber = "8801911111111"; c.TerminatingCallingNumber = "8801911111111";
        c.StartTime = WHEN; c.AnswerTime = WHEN; c.ChargingStatus = 1; c.DurationSec = BigDecimal.valueOf(60);
        return c;
    }
}
