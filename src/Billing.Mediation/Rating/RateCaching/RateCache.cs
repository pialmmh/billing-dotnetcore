// Faithful port of legacy Mediation/Mediation/RateCache.cs — the MAIN rate cache, loaded PER DAY.
//   DateRangeWiseRateDic : DateRange -> TupleByPeriod -> prefix -> List<rateassign>
//   GetRateDictsByDay    : lazy-load the day via PopulateDicByDay, then return it (legacy GetRateDictsByDay)
//   PopulateDicByDay     : the one-day load (legacy InsertRatesToTempTable + GetRateDic* + RateListToDictionary),
//                          here behind IRateLoader so the DB query lives in the data layer.
// rateassign stands in for the legacy Rateext : rate (same matched fields: Prefix/Category/SubCategory/
// startdate/enddate). The legacy date-RANGE loading is reduced to the involved day, per the architect's
// today-only note; the cache + lookup keep the exact shape.
#nullable disable
using System;
using System.Collections.Generic;
using LibraryExtensions;
using MediationModel;

namespace TelcobrightMediation
{
    public class RateCache
    {
        private static readonly object Locker = new object();
        private readonly IRateLoader _loader;

        // MAIN RATE CACHE — all rates loaded per day in the cache.
        public Dictionary<DateRange, Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>>> DateRangeWiseRateDic
            = new Dictionary<DateRange, Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>>>(new DateRange.EqualityComparer());

        public RateCache(IRateLoader loader) => _loader = loader;

        public Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>> GetRateDictsByDay(DateRange dRange)
        {
            this.DateRangeWiseRateDic.TryGetValue(dRange, out var todaysDict);
            if (todaysDict == null)
            {
                PopulateDicByDay(dRange);
                this.DateRangeWiseRateDic.TryGetValue(dRange, out todaysDict);
            }
            return todaysDict;
        }

        public void PopulateDicByDay(DateRange dRange)
        {
            lock (Locker)
            {
                if (this.DateRangeWiseRateDic.ContainsKey(dRange)) return;
                var dicByDay = _loader.LoadDay(dRange);
                this.DateRangeWiseRateDic.Add(dRange, dicByDay);
            }
        }

        // allow resuming after a memory exception at any stage (legacy ClearRateCache).
        public void ClearRateCache()
        {
            this.DateRangeWiseRateDic.Clear();
            GC.Collect();
        }
    }
}
