// Faithful port of legacy Mediation/Mediation/RateCache.cs — the MAIN rate cache, loaded PER DAY.
//   DateRangeWiseRateDic : DateRange -> TupleByPeriod -> prefix -> List<Rateext>   (legacy DateRangeWiseRateDic)
//   DicRatePlan          : idrateplan-as-string -> rateplan                        (legacy RateCache.DicRatePlan)
//   GetRateDictsByDay    : lazy-load the day via PopulateDicByDay, then return it  (legacy GetRateDictsByDay)
//   PopulateDicByDay     : the one-day load, here behind IRateLoader (legacy PopulateDicByDay + the
//                          RateDictionaryGeneratorByTuples/RateList build).
// The MATCHED entity is Rateext (rate + the rate-plan-assignment overlay), exactly as in legacy; PrefixMatcher
// reads its P_Startdate()/P_Enddate(). The legacy date-RANGE loading is reduced to the involved day (today-only
// note); the cache + lookup keep the exact shape.
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import com.telcobright.billing.mediation.engine.models.rateplan;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RateCache {
    public static final int DEFAULT_MAX_DAYS = 7;

    private final IRateLoader _loader;
    private final int _maxDays;

    // One lock object per day being loaded: a request for a day that is not cached BLOCKS only other requests
    // for the SAME day (they wait on that load, then reuse it) while different days load in parallel. Replaces
    // the legacy single static Locker, which serialized every day and every tenant.
    private final ConcurrentHashMap<DateRange, Object> _dateLocks = new ConcurrentHashMap<>();

    // MAIN RATE CACHE — all rates loaded per day in the cache.
    // (Legacy constructed this with a DateRange.EqualityComparer; the map uses DateRange's own
    //  equals()/hashCode(), which implements the same (StartDate, EndDate) value-equality.)
    // ConcurrentHashMap because gRPC worker threads read (GetRateDictsByDay's unsynchronized get)
    // while a load writes under a per-day lock — a plain HashMap gave no happens-before for the first
    // read and could return a torn/spurious-null view.
    public Map<DateRange, Map<TupleByPeriod, Map<String, List<Rateext>>>> DateRangeWiseRateDic =
        new ConcurrentHashMap<>();

    // legacy RateCache.DicRatePlan — key = idrateplan as string. The rater reads field4 (techPrefix),
    // BillingSpan (uom) and RateAmountRoundupDecimal from here.
    public Map<String, rateplan> DicRatePlan = new HashMap<>();

    public RateCache(IRateLoader loader) {
        this(loader, null, DEFAULT_MAX_DAYS);
    }

    public RateCache(IRateLoader loader, Map<String, rateplan> dicRatePlan) {
        this(loader, dicRatePlan, DEFAULT_MAX_DAYS);
    }

    public RateCache(IRateLoader loader, Map<String, rateplan> dicRatePlan, int maxDays) {
        _loader = loader;
        this.DicRatePlan = dicRatePlan != null ? dicRatePlan : new HashMap<>();
        _maxDays = maxDays > 0 ? maxDays : DEFAULT_MAX_DAYS;
    }

    /**
     * Return one day's rates, loading it lazily if absent. Loading a day that is not cached BLOCKS the caller
     * until it is loaded (realtime today/tomorrow are pre-warmed so they don't block; a back-dated CDR waits
     * while its day is fetched from config-manager). Concurrent callers for the SAME day wait on one load;
     * different days load in parallel.
     */
    public Map<TupleByPeriod, Map<String, List<Rateext>>> GetRateDictsByDay(DateRange dRange) {
        var cached = this.DateRangeWiseRateDic.get(dRange);
        if (cached != null) return cached;

        Object lock = _dateLocks.computeIfAbsent(dRange, k -> new Object());
        synchronized (lock) {
            cached = this.DateRangeWiseRateDic.get(dRange);
            if (cached != null) return cached;

            EvictIfFullForNewDate(dRange);                       // may flush-all + re-warm today/tomorrow
            cached = this.DateRangeWiseRateDic.get(dRange);      // eviction may have just re-warmed this day
            if (cached != null) return cached;

            var dicByDay = _loader.LoadDay(dRange);              // may block on a config-manager fetch (back-date)
            this.DateRangeWiseRateDic.put(dRange, dicByDay);
            return dicByDay;
        }
    }

    // Kept for API compatibility (legacy PopulateDicByDay): ensure the day is loaded.
    public void PopulateDicByDay(DateRange dRange) {
        GetRateDictsByDay(dRange);
    }

    /**
     * The window guard: when the cache already holds {@code maxDays} days and a NEW day is requested, flush ALL
     * then re-warm today + tomorrow (the requested target day is loaded by the caller right after). This bounds
     * memory during back-processing while keeping the realtime days present — today/tomorrow are never what gets
     * evicted, they are reloaded immediately.
     */
    private synchronized void EvictIfFullForNewDate(DateRange dRange) {
        if (this.DateRangeWiseRateDic.containsKey(dRange)) return;   // another thread already loaded it
        if (this.DateRangeWiseRateDic.size() < _maxDays) return;     // room to spare
        this.DateRangeWiseRateDic.clear();
        WarmProtectedDays();
    }

    // Load today + tomorrow if absent (in-memory from the snapshot on the realtime path; a fetch only if the
    // context has lived past the day it was built for). Used by the flush above and the every-minute guard.
    private void WarmProtectedDays() {
        LocalDate today = LocalDate.now();
        for (LocalDate d : List.of(today, today.plusDays(1))) {
            DateRange dr = DayRange(d);
            this.DateRangeWiseRateDic.computeIfAbsent(dr, x -> _loader.LoadDay(x));
        }
    }

    /**
     * Ensure today + tomorrow are loaded — the every-minute guard (RateCacheGuard) calls this so a realtime
     * call never has to wait, even right after a back-processing flush or a config reload.
     */
    public void EnsureTodayTomorrow() {
        LocalDate today = LocalDate.now();
        GetRateDictsByDay(DayRange(today));
        GetRateDictsByDay(DayRange(today.plusDays(1)));
    }

    /** The RateCache day key for a calendar date: [d 00:00, d+1 00:00) — matches BasicCharge.MatchRate. */
    public static DateRange DayRange(LocalDate d) {
        return new DateRange(d.atStartOfDay(), d.atStartOfDay().plusDays(1));
    }

    // allow resuming after a memory exception at any stage (legacy ClearRateCache).
    public void ClearRateCache() {
        this.DateRangeWiseRateDic.clear();
        System.gc();
    }
}
