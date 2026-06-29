// The DB-load side of the RateCache (the legacy PopulateDicByDay + RateDictionaryGeneratorByTuples +
// RateList, behind one seam). LoadDay returns one day's rates as TupleByPeriod -> prefix -> rates —
// exactly the DateRangeWiseRateDic[day] shape. The MySql implementation reads the rate /
// rateplanassignmenttuple tables; tests use an in-memory loader.
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.rateassign;
import java.util.List;
import java.util.Map;

public interface IRateLoader {
    Map<TupleByPeriod, Map<String, List<rateassign>>> LoadDay(DateRange dRange);
}
