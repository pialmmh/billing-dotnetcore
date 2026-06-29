package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The legacy PrefixMatcher over the per-day RateCache: priority across tuples (outer), longest-prefix within a
 * tuple (middle), Category/SubCategory + [P_Startdate, P_Enddate) validity (inner). The cache is fed by the
 * config-fed TupleRateLoader (tuple -> rateassign(Inactive=idRatePlan) -> rate plan -> rate rows), so this also
 * exercises the day-overlap filter (future/expired rates are dropped before the matcher even runs).
 */
class PrefixMatcherTests {
    private static final LocalDateTime Now = LocalDateTime.of(2026, 6, 19, 0, 0);

    private static DateRange Day() {
        return new DateRange(Now.toLocalDate().atStartOfDay(), Now.toLocalDate().atStartOfDay().plusDays(1));
    }

    private static Rateext Match(TestData.Fixture f, String number, int category, int subCategory) {
        return new PrefixMatcher(f.rateCache(), number, category, subCategory, f.tupsForDay(Day()), Now).MatchPrefix();
    }

    @Test
    void Longest_prefix_wins_within_a_tuple() {
        var f = TestData.fixture();
        f.tup(10, 1, 5, null, 0, TestData.Ra(1, "1.0"), TestData.Ra(17, "2.0"), TestData.Ra(171, "3.0"));

        assertEquals("171", Match(f, "1712345", 1, 1).Prefix);   // longest (171)
        assertEquals("17", Match(f, "1799999", 1, 1).Prefix);    // 17 (no 171 match)
        assertEquals("1", Match(f, "1500000", 1, 1).Prefix);     // 1
        assertNull(Match(f, "9000000", 1, 1));                    // none
    }

    @Test
    void Lowest_priority_tuple_wins() {
        var f = TestData.fixture();
        f.tup(10, 1, 5, null, 5, TestData.Ra(1, "10.0").idRatePlan(8));   // higher priority number = later
        f.tup(10, 1, 5, null, 0, TestData.Ra(1, "5.0").idRatePlan(7));    // priority 0 wins

        assertEquals(0, new BigDecimal("5.0").compareTo(Match(f, "1234", 1, 1).rateamount));
    }

    @Test
    void Category_or_subcategory_mismatch_does_not_match() {
        var f = TestData.fixture();
        f.tup(10, 1, 5, null, 0, TestData.Ra(1, "1.0").category(2));
        assertNull(Match(f, "1234", 1, 1));
    }

    @Test
    void Outside_validity_window_does_not_match() {
        var f = TestData.fixture();
        f.tup(10, 1, 5, null, 0,
                TestData.Ra(1, "1.0").startdate(LocalDateTime.of(2027, 1, 1, 0, 0)),   // not yet effective
                TestData.Ra(2, "1.0").enddate(LocalDateTime.of(2025, 1, 1, 0, 0)));    // expired

        assertNull(Match(f, "1234", 1, 1));   // future rate dropped by the day-overlap filter
        assertNull(Match(f, "2345", 1, 1));   // expired rate dropped by the day-overlap filter
    }
}
