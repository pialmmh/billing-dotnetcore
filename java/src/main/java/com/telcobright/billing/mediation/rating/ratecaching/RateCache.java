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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RateCache {
    private static final Object Locker = new Object();
    private final IRateLoader _loader;

    // MAIN RATE CACHE — all rates loaded per day in the cache.
    // (Legacy constructed this with a DateRange.EqualityComparer; Java HashMap uses DateRange's own
    //  equals()/hashCode(), which implements the same (StartDate, EndDate) value-equality.)
    public Map<DateRange, Map<TupleByPeriod, Map<String, List<Rateext>>>> DateRangeWiseRateDic =
        new HashMap<>();

    // legacy RateCache.DicRatePlan — key = idrateplan as string. The rater reads field4 (techPrefix),
    // BillingSpan (uom) and RateAmountRoundupDecimal from here.
    public Map<String, rateplan> DicRatePlan = new HashMap<>();

    public RateCache(IRateLoader loader) {
        _loader = loader;
    }

    public RateCache(IRateLoader loader, Map<String, rateplan> dicRatePlan) {
        _loader = loader;
        this.DicRatePlan = dicRatePlan != null ? dicRatePlan : new HashMap<>();
    }

    public Map<TupleByPeriod, Map<String, List<Rateext>>> GetRateDictsByDay(DateRange dRange) {
        var todaysDict = this.DateRangeWiseRateDic.get(dRange);
        if (todaysDict == null) {
            PopulateDicByDay(dRange);
            todaysDict = this.DateRangeWiseRateDic.get(dRange);
        }
        return todaysDict;
    }

    public void PopulateDicByDay(DateRange dRange) {
        synchronized (Locker) {
            if (this.DateRangeWiseRateDic.containsKey(dRange)) return;
            var dicByDay = _loader.LoadDay(dRange);
            this.DateRangeWiseRateDic.put(dRange, dicByDay);
        }
    }

    // allow resuming after a memory exception at any stage (legacy ClearRateCache).
    public void ClearRateCache() {
        this.DateRangeWiseRateDic.clear();
        System.gc();
    }
}
