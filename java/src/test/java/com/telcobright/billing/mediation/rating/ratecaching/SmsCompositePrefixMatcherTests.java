package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.model.AssignmentDirection;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The SMS calling+called matcher in isolation (synthetic prefixes). Semantics pinned: routesphere's
 * {@code RatePrefixMatcher} (each non-empty side startsWith, case-sensitive; most specific = longest combined, then
 * longer calling, then smaller raw key; no-0x1F = destination-only) inside the legacy PrefixMatcher frame
 * (priority order, validity, last-valid-per-prefix), with the plan's tech prefix ignored.
 */
class SmsCompositePrefixMatcherTests {
    private static final char US = '\u001F';
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 10, 5, 11, 15, 51);

    private static TestData.Ra Row(String prefix, String amount) {
        var ra = TestData.Ra(0, amount);
        ra.build().Prefix = prefix;
        return ra;
    }

    private static Rateext Match(TestData.Fixture f, String calling, String called, IntPredicate filter) {
        var day = RateCache.DayRange(LocalDate.of(2026, 10, 5));
        return new SmsCompositePrefixMatcher(f.rateCache(), calling, called, 1, 1, f.tupsForDay(day), WHEN, filter).Match();
    }

    private static Rateext Match(TestData.Fixture f, String calling, String called) {
        return Match(f, calling, called, t -> true);
    }

    private static TestData.Fixture Plan(TestData.Ra... rows) {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, rows);
        return f;
    }

    @Test
    void composite_beats_destination_only_and_destination_only_is_the_fallback() {
        var f = Plan(Row("BRAND" + US + "8801", "0.55"), Row("8801", "0.33"));
        assertEquals("BRAND" + US + "8801", Match(f, "BRAND", "8801700000000").RawPrefix);
        assertEquals("8801", Match(f, "8809600000000", "8801700000000").RawPrefix, "no sender match -> destination-only fallback");
    }

    @Test
    void both_sides_must_match() {
        var f = Plan(Row("BRAND" + US + "8801", "0.55"));
        assertNull(Match(f, "BRAND", "8851234567"), "called side must match too");
        assertNull(Match(f, "OTHER", "8801700000000"), "calling side must match too");
    }

    @Test
    void sender_match_is_case_sensitive() {
        var f = Plan(Row("BRAND" + US + "8801", "0.55"), Row("8801", "0.33"));
        assertEquals("8801", Match(f, "brand", "8801700000000").RawPrefix);
    }

    @Test
    void equal_total_prefers_the_longer_calling_side() {
        var f = Plan(Row("88" + US + "8801", "0.10"), Row("8809" + US + "88", "0.20"));
        assertEquals("8809" + US + "88", Match(f, "8809600000000", "8801700000000").RawPrefix);
    }

    @Test
    void full_tie_prefers_the_lexicographically_smaller_raw_key() {
        var f = Plan(Row("12", "0.10"), Row(US + "12", "0.20"));    // both: calling "" + called "12"
        assertEquals(US + "12", Match(f, "x", "1234").RawPrefix, "\\u001F12 < 12");
    }

    @Test
    void longer_combined_wins_over_sender_only() {
        var f = Plan(Row("ACME" + US, "0.40"), Row(US + "88017", "0.30"));
        assertEquals(US + "88017", Match(f, "ACME", "8801712345678").RawPrefix, "called-only total 5 beats sender-only 4");
        var g = Plan(Row("BRAND" + US, "0.40"), Row(US + "88017", "0.30"));
        assertEquals("BRAND" + US, Match(g, "BRAND", "8801712345678").RawPrefix, "tie at 5: the longer calling side wins");
    }

    @Test
    void separator_only_row_is_a_lowest_specificity_catch_all() {
        var f = Plan(Row(String.valueOf(US), "0.99"), Row("8801", "0.33"));
        assertEquals("8801", Match(f, "x", "8801700000000").RawPrefix);
        assertEquals(String.valueOf(US), Match(f, "x", "9999").RawPrefix, "routesphere: both sides empty match anything");
    }

    @Test
    void the_plan_tech_prefix_is_ignored_for_sms_but_voice_still_applies_it() {
        var f = Plan(Row("8801", "0.33"));
        f.dicRatePlan.get("7").field4 = "99";                       // voice keys this row as "998801"
        assertNotNull(Match(f, "x", "8801700000000"), "SMS ignores field4 (routesphere semantics)");

        var day = RateCache.DayRange(LocalDate.of(2026, 10, 5));
        var voice = new PrefixMatcher(f.rateCache(), "8801700000000", 1, 1, f.tupsForDay(day), WHEN).MatchPrefix();
        assertNull(voice, "voice behaviour unchanged: the tech prefix still keys the row");
    }

    @Test
    void lower_priority_tuple_is_tried_first_and_wins_when_it_matches() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, Row("8801", "0.11").idRatePlan(1));
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 1, Row("BRAND" + US + "8801", "0.55").idRatePlan(2));
        assertEquals("0.11", Match(f, "BRAND", "8801700000000").rateamount.toPlainString(),
                "first priority with ANY match wins (legacy), even over a more specific later-priority row");
    }

    @Test
    void falls_through_to_the_next_priority_when_the_first_has_no_match() {
        var f = TestData.fixture();
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 0, Row("8851", "0.11").idRatePlan(1));
        f.tup(10, AssignmentDirection.Customer.value, 5, null, 1, Row("8801", "0.22").idRatePlan(2));
        assertEquals("0.22", Match(f, "x", "8801700000000").rateamount.toPlainString());
    }

    @Test
    void a_rate_not_yet_effective_is_not_valid() {
        var f = Plan(Row("8801", "0.33").startdate(WHEN.plusDays(1)));
        assertNull(Match(f, "x", "8801700000000"));
    }

    @Test
    void earliest_valid_start_wins_among_same_prefix_rows() {
        var f = Plan(Row("8801", "0.40").startdate(WHEN.minusDays(1)), Row("8801", "0.30").startdate(WHEN.minusDays(10)));
        assertEquals("0.30", Match(f, "x", "8801700000000").rateamount.toPlainString(), "legacy: last valid in start-DESC order");
    }

    @Test
    void the_tuple_filter_excludes_tuples() {
        var f = Plan(Row("8801", "0.33"));
        assertNull(Match(f, "x", "8801700000000", t -> false));
    }

    @Test
    void category_must_match() {
        var f = Plan(Row("8801", "0.33").category(2));
        assertNull(Match(f, "x", "8801700000000"));
    }
}
