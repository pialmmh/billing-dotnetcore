// The CONFIG-DRIVEN IRateLoader — the live-flow counterpart of MySqlRateLoader. config-manager serves a
// tenant's rateplanassignmenttuples WITH their nested rateassigns, so the rates are already in memory; this
// loader projects them into one day's DateRangeWiseRateDic[day] shape (TupleByPeriod -> prefix -> rates)
// instead of querying the DB. Same shape, same day-validity filter (the legacy temp_rate overlap predicate)
// and same group-by-prefix-desc-by-startdate as MySqlRateLoader, so the RateCache + PrefixMatcher behave
// identically whether the day is loaded from config or from the database.
#nullable disable
using System;
using System.Collections.Generic;
using System.Linq;
using LibraryExtensions;
using MediationModel;

namespace TelcobrightMediation
{
    public sealed class TupleRateLoader : IRateLoader
    {
        private static readonly DateTime MaxDate = new DateTime(9999, 12, 31, 23, 59, 59);
        private readonly IReadOnlyList<rateplanassignmenttuple> _tuples;

        public TupleRateLoader(IReadOnlyList<rateplanassignmenttuple> tuples) =>
            _tuples = tuples ?? new List<rateplanassignmenttuple>();

        public Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>> LoadDay(DateRange dRange)
        {
            var result = new Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>>(
                new TupleByPeriod.EqualityComparer());

            foreach (var tuple in _tuples)
            {
                var rates = (tuple.rateassigns ?? new List<rateassign>())
                    .Where(r => ValidForDay(r, dRange))
                    .ToList();
                if (rates.Count == 0) continue;

                // RateListToDictionary: group by prefix, latest-start first.
                var byPrefix = new Dictionary<string, List<rateassign>>();
                foreach (var rate in rates.OrderByDescending(x => x.startdate))
                {
                    var prefix = rate.Prefix.ToString();
                    if (!byPrefix.TryGetValue(prefix, out var list)) byPrefix[prefix] = list = new List<rateassign>();
                    list.Add(rate);
                }

                result[new TupleByPeriod { IdAssignmentTuple = tuple.id, DRange = dRange, Priority = tuple.priority }] = byPrefix;
            }
            return result;
        }

        // legacy temp_rate overlap predicate: an active rate whose validity intersects the day.
        private static bool ValidForDay(rateassign r, DateRange dRange)
        {
            if (r.Inactive != 0) return false;
            var dayStart = dRange.StartDate;
            var dayEnd = dRange.EndDate;
            var end = r.enddate ?? MaxDate;
            return (r.startdate <= dayStart && end > dayStart)
                   || (r.startdate >= dayStart && r.startdate < dayEnd);
        }
    }
}
