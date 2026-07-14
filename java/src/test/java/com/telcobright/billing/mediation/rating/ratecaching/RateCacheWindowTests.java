package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The new RateCache behaviour: lazy per-date load (once, even under concurrency), the maxDays window with
 *  flush-all-on-overflow, and the today+tomorrow re-warm / EnsureTodayTomorrow guard hook. */
class RateCacheWindowTests {

    /** Records how many times each day was built; content is irrelevant to these tests. */
    static final class CountingLoader implements IRateLoader {
        final Map<DateRange, Integer> loads = new ConcurrentHashMap<>();

        @Override
        public Map<TupleByPeriod, Map<String, List<Rateext>>> LoadDay(DateRange dRange) {
            loads.merge(dRange, 1, Integer::sum);
            return new HashMap<>();
        }
    }

    @Test
    void lazy_loads_each_day_once() {
        CountingLoader loader = new CountingLoader();
        RateCache cache = new RateCache(loader, new HashMap<>(), 7);
        DateRange d = RateCache.DayRange(LocalDate.of(2021, 6, 1));

        cache.GetRateDictsByDay(d);
        cache.GetRateDictsByDay(d);

        assertEquals(1, loader.loads.get(d), "a cached day must not reload");
    }

    @Test
    void flush_all_when_full_then_rewarms_today_and_tomorrow() {
        CountingLoader loader = new CountingLoader();
        RateCache cache = new RateCache(loader, new HashMap<>(), 3);   // small window to force overflow

        // fill the window with 3 distinct OLD days
        cache.GetRateDictsByDay(RateCache.DayRange(LocalDate.of(2020, 1, 1)));
        cache.GetRateDictsByDay(RateCache.DayRange(LocalDate.of(2020, 1, 2)));
        cache.GetRateDictsByDay(RateCache.DayRange(LocalDate.of(2020, 1, 3)));
        assertEquals(3, cache.DateRangeWiseRateDic.size());

        // a 4th NEW day: flush ALL, re-warm today+tomorrow, then load the target
        DateRange target = RateCache.DayRange(LocalDate.of(2020, 1, 4));
        cache.GetRateDictsByDay(target);

        DateRange today = RateCache.DayRange(LocalDate.now());
        DateRange tomorrow = RateCache.DayRange(LocalDate.now().plusDays(1));
        assertTrue(cache.DateRangeWiseRateDic.containsKey(today), "today re-warmed after flush");
        assertTrue(cache.DateRangeWiseRateDic.containsKey(tomorrow), "tomorrow re-warmed after flush");
        assertTrue(cache.DateRangeWiseRateDic.containsKey(target), "the requested target day is loaded");
        assertFalse(cache.DateRangeWiseRateDic.containsKey(RateCache.DayRange(LocalDate.of(2020, 1, 1))),
                "the old days were flushed");
        assertEquals(3, cache.DateRangeWiseRateDic.size(), "window holds today, tomorrow, target");
    }

    @Test
    void ensure_today_tomorrow_loads_both() {
        CountingLoader loader = new CountingLoader();
        RateCache cache = new RateCache(loader, new HashMap<>(), 7);

        cache.EnsureTodayTomorrow();

        assertTrue(cache.DateRangeWiseRateDic.containsKey(RateCache.DayRange(LocalDate.now())));
        assertTrue(cache.DateRangeWiseRateDic.containsKey(RateCache.DayRange(LocalDate.now().plusDays(1))));
    }

    @Test
    void concurrent_requests_for_the_same_day_load_it_once() throws InterruptedException {
        CountingLoader loader = new CountingLoader();
        RateCache cache = new RateCache(loader, new HashMap<>(), 7);
        DateRange d = RateCache.DayRange(LocalDate.of(2019, 3, 3));

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try { go.await(); cache.GetRateDictsByDay(d); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
        }
        go.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, loader.loads.get(d), "per-date lock must collapse concurrent loads to one");
    }
}
