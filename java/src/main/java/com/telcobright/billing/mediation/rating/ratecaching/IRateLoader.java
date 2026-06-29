// The build side of the RateCache — the legacy PopulateDicByDay (RateDictionaryGeneratorByTuples + RateList)
// behind one seam. LoadDay returns ONE day's rates as TupleByPeriod -> prefix -> List<Rateext>, exactly the
// DateRangeWiseRateDic[day] shape. The live flow is config-fed (TupleRateLoader over the served tuples +
// rate rows + rate plans); tests use an in-memory Function-backed loader.
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.Rateext;
import java.util.List;
import java.util.Map;

public interface IRateLoader {
    Map<TupleByPeriod, Map<String, List<Rateext>>> LoadDay(DateRange dRange);
}
