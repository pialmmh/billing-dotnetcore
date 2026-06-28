// The DB-load side of the RateCache (the legacy PopulateDicByDay + RateDictionaryGeneratorByTuples +
// RateList, behind one seam). LoadDay returns one day's rates as TupleByPeriod -> prefix -> rates —
// exactly the DateRangeWiseRateDic[day] shape. The MySql implementation reads the rate /
// rateplanassignmenttuple tables; tests use an in-memory loader.
#nullable disable
using System.Collections.Generic;
using LibraryExtensions;
using MediationModel;

namespace TelcobrightMediation
{
    public interface IRateLoader
    {
        Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>> LoadDay(DateRange dRange);
    }
}
