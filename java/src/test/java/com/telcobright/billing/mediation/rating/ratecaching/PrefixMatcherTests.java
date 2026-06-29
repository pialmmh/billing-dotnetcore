package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The legacy PrefixMatcher over the per-day RateCache: priority across tuples (outer), longest-prefix within
 * a tuple (middle), Category/SubCategory + [startdate, enddate) validity (inner); first match wins. The cache
 * is fed by TupleRateLoader, so this also exercises the config-driven day load (future/expired rates are
 * filtered out by the day-overlap predicate before the matcher even runs).
 *
 * <p>Faithful port of PrefixMatcherTests.cs. The C# {@code Now}/{@code Day} members and the {@code params}
 * {@code Match} helper are kept; C#'s {@code TestData.Ra(...)}/{@code Tup(...)} named-args become the fluent
 * builder; {@code rateassign?} returns become plain (nullable) references.</p>
 */
class PrefixMatcherTests {
    private static final LocalDateTime Now = LocalDateTime.of(2026, 6, 19, 0, 0);

    // C# property `Day => new(Now.Date, Now.Date.AddDays(1))` — a fresh DateRange on each access.
    private static DateRange Day() {
        return new DateRange(Now.toLocalDate().atStartOfDay(), Now.toLocalDate().atStartOfDay().plusDays(1));
    }

    // Match a number through a fresh RateCache built over the given tuples (each tuple's nested rateassigns
    // become its per-day prefix dictionary, exactly as in the live config-driven flow).
    private static rateassign Match(String number, int category, int subCategory,
            rateplanassignmenttuple... tuples) {
        var cache = new RateCache(new TupleRateLoader(Arrays.asList(tuples)));
        var tups = Arrays.stream(tuples)
                .map(t -> {
                    TupleByPeriod tp = new TupleByPeriod();
                    tp.IdAssignmentTuple = t.id;
                    tp.DRange = Day();
                    tp.Priority = t.priority;
                    return tp;
                })
                .collect(Collectors.toList());
        return new PrefixMatcher(cache, number, category, subCategory, tups, Now).MatchPrefix();
    }

    @Test
    void Longest_prefix_wins_within_a_tuple() {
        var tup = TestData.Tup(10, 1, 5, null, 0,
                TestData.Ra(1, "1.0"), TestData.Ra(17, "2.0"), TestData.Ra(171, "3.0"));

        assertEquals(171, Match("1712345", 1, 1, tup).Prefix);   // longest (171)
        assertEquals(17, Match("1799999", 1, 1, tup).Prefix);    // 17 (no 171 match)
        assertEquals(1, Match("1500000", 1, 1, tup).Prefix);     // 1
        assertNull(Match("9000000", 1, 1, tup));                  // none
    }

    @Test
    void Lowest_priority_tuple_wins() {
        var hi = TestData.Tup(10, 1, 5, null, 5, TestData.Ra(1, "10.0"));
        var lo = TestData.Tup(10, 1, 5, null, 0, TestData.Ra(1, "5.0"));

        assertEquals(0, new BigDecimal("5.0").compareTo(Match("1234", 1, 1, hi, lo).rateamount));   // priority-0 tuple wins
    }

    @Test
    void Category_or_subcategory_mismatch_does_not_match() {
        var tup = TestData.Tup(10, 1, 5, null, 0, TestData.Ra(1, "1.0").category(2));
        assertNull(Match("1234", 1, 1, tup));
    }

    @Test
    void Outside_validity_window_does_not_match() {
        var future = TestData.Ra(1, "1.0").startdate(LocalDateTime.of(2027, 1, 1, 0, 0));
        var expired = TestData.Ra(2, "1.0").enddate(LocalDateTime.of(2025, 1, 1, 0, 0));
        var tup = TestData.Tup(10, 1, 5, null, 0, future, expired);

        assertNull(Match("1234", 1, 1, tup));   // not yet effective
        assertNull(Match("2345", 1, 1, tup));   // expired
    }
}
