// Faithful port of legacy Mediation/Mediation/RateCache.cs — the MAIN rate cache, loaded PER DAY.
//   DateRangeWiseRateDic : DateRange -> TupleByPeriod -> prefix -> List<rateassign>
//   GetRateDictsByDay    : lazy-load the day via PopulateDicByDay, then return it (legacy GetRateDictsByDay)
//   PopulateDicByDay     : the one-day load (legacy InsertRatesToTempTable + GetRateDic* + RateListToDictionary),
//                          here behind IRateLoader so the DB query lives in the data layer.
// rateassign stands in for the legacy Rateext : rate (same matched fields: Prefix/Category/SubCategory/
// startdate/enddate). The legacy date-RANGE loading is reduced to the involved day, per the architect's
// today-only note; the cache + lookup keep the exact shape.
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.rateassign;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RateCache {
    private static final Object Locker = new Object();
    private final IRateLoader _loader;

    // MAIN RATE CACHE — all rates loaded per day in the cache.
    // (Legacy constructed this with a DateRange.EqualityComparer; Java HashMap uses DateRange's own
    //  equals()/hashCode(), which implements the same (StartDate, EndDate) value-equality.)
    public Map<DateRange, Map<TupleByPeriod, Map<String, List<rateassign>>>> DateRangeWiseRateDic =
        new HashMap<>();

    public RateCache(IRateLoader loader) { _loader = loader; }

    public Map<TupleByPeriod, Map<String, List<rateassign>>> GetRateDictsByDay(DateRange dRange) {
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
