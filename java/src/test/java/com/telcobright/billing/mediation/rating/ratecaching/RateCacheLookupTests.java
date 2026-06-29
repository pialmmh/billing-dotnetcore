package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.testsupport.TestData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rate lookup THROUGH the per-day RateCache + the legacy PrefixMatcher: the cache loads the day once (lazily),
 * and PrefixMatcher longest-prefixes over that day's per-tuple prefix dictionaries.
 *
 * <p>Faithful port of RateCacheLookupTests.cs. The C# {@code Func<>}-backed in-memory loader becomes a
 * {@link Function}-backed one; the C# dictionary built with a {@code TupleByPeriod.EqualityComparer} becomes a
 * plain {@link HashMap} (Java uses the key's own equals()/hashCode(), the same value-equality).</p>
 */
class RateCacheLookupTests {

    private static final class InMemoryRateLoader implements IRateLoader {
        private final Function<DateRange, Map<TupleByPeriod, Map<String, List<rateassign>>>> _load;
        int Loads = 0;   // C# `public int Loads { get; private set; }`

        InMemoryRateLoader(Function<DateRange, Map<TupleByPeriod, Map<String, List<rateassign>>>> load) {
            _load = load;
        }

        @Override
        public Map<TupleByPeriod, Map<String, List<rateassign>>> LoadDay(DateRange dRange) {
            Loads++;
            return _load.apply(dRange);
        }
    }

    private static final LocalDateTime Today = LocalDateTime.of(2026, 6, 19, 0, 0);

    // C# property `Day => new(Today, Today.AddDays(1))` — a fresh DateRange on each access.
    private static DateRange Day() {
        return new DateRange(Today, Today.plusDays(1));
    }

    private static Map<TupleByPeriod, Map<String, List<rateassign>>> OneTupleDay(DateRange d) {
        Map<String, List<rateassign>> prefixDic = new HashMap<>();
        prefixDic.put("1", new ArrayList<>(List.of(TestData.Ra(1, "1.0").build())));
        prefixDic.put("17", new ArrayList<>(List.of(TestData.Ra(17, "2.0").build())));
        prefixDic.put("171", new ArrayList<>(List.of(TestData.Ra(171, "3.0").build())));

        // C# constructed this with `new TupleByPeriod.EqualityComparer()`; Java's HashMap uses the key's own
        // equals()/hashCode(), which implements the same (IdAssignmentTuple, DRange) value-equality.
        Map<TupleByPeriod, Map<String, List<rateassign>>> result = new HashMap<>();
        TupleByPeriod key = new TupleByPeriod();
        key.IdAssignmentTuple = 1;
        key.DRange = d;
        key.Priority = 0;
        result.put(key, prefixDic);
        return result;
    }

    @Test
    void Lookup_through_ratecache_longest_prefix_wins() {
        var cache = new RateCache(new InMemoryRateLoader(RateCacheLookupTests::OneTupleDay));
        List<TupleByPeriod> tups = new ArrayList<>();
        TupleByPeriod tp = new TupleByPeriod();
        tp.IdAssignmentTuple = 1;
        tp.DRange = Day();
        tp.Priority = 0;
        tups.add(tp);

        assertEquals(171, new PrefixMatcher(cache, "1712345", 1, 1, tups, Today).MatchPrefix().Prefix);
        assertEquals(17, new PrefixMatcher(cache, "1799999", 1, 1, tups, Today).MatchPrefix().Prefix);
        assertEquals(1, new PrefixMatcher(cache, "1500000", 1, 1, tups, Today).MatchPrefix().Prefix);
        assertNull(new PrefixMatcher(cache, "9000000", 1, 1, tups, Today).MatchPrefix());
    }

    @Test
    void Ratecache_loads_the_day_once_then_serves_from_cache() {
        var loader = new InMemoryRateLoader(RateCacheLookupTests::OneTupleDay);
        var cache = new RateCache(loader);

        cache.GetRateDictsByDay(Day());
        cache.GetRateDictsByDay(Day());   // second call: served from DateRangeWiseRateDic, no reload

        assertEquals(1, loader.Loads);
    }
}
