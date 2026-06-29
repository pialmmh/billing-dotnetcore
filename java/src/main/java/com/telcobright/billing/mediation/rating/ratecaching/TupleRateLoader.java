// The CONFIG-DRIVEN IRateLoader — the live-flow counterpart of MySqlRateLoader. config-manager serves a
// tenant's rateplanassignmenttuples WITH their nested rateassigns, so the rates are already in memory; this
// loader projects them into one day's DateRangeWiseRateDic[day] shape (TupleByPeriod -> prefix -> rates)
// instead of querying the DB. Same shape, same day-validity filter (the legacy temp_rate overlap predicate)
// and same group-by-prefix-desc-by-startdate as MySqlRateLoader, so the RateCache + PrefixMatcher behave
// identically whether the day is loaded from config or from the database.
package com.telcobright.billing.mediation.rating.ratecaching;

import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.engine.models.rateplanassignmenttuple;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TupleRateLoader implements IRateLoader {
    private static final LocalDateTime MaxDate = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
    private final List<rateplanassignmenttuple> _tuples;

    public TupleRateLoader(List<rateplanassignmenttuple> tuples) {
        _tuples = tuples != null ? tuples : new ArrayList<rateplanassignmenttuple>();
    }

    @Override
    public Map<TupleByPeriod, Map<String, List<rateassign>>> LoadDay(DateRange dRange) {
        // (Legacy constructed this with a TupleByPeriod.EqualityComparer; Java HashMap uses TupleByPeriod's
        //  own equals()/hashCode(), which implements the same (IdAssignmentTuple, DRange) value-equality.)
        var result = new HashMap<TupleByPeriod, Map<String, List<rateassign>>>();

        for (var tuple : _tuples) {
            var rates = (tuple.rateassigns != null ? tuple.rateassigns : new ArrayList<rateassign>())
                .stream()
                .filter(r -> ValidForDay(r, dRange))
                .toList();
            if (rates.size() == 0) continue;

            // RateListToDictionary: group by prefix, latest-start first.
            var byPrefix = new HashMap<String, List<rateassign>>();
            for (var rate : rates.stream().sorted(Comparator.comparing((rateassign x) -> x.startdate).reversed()).toList()) {
                var prefix = Integer.toString(rate.Prefix);
                var list = byPrefix.get(prefix);
                if (list == null) { list = new ArrayList<rateassign>(); byPrefix.put(prefix, list); }
                list.add(rate);
            }

            var key = new TupleByPeriod();
            key.IdAssignmentTuple = tuple.id;
            key.DRange = dRange;
            key.Priority = tuple.priority;
            result.put(key, byPrefix);
        }
        return result;
    }

    // legacy temp_rate overlap predicate: an active rate whose validity intersects the day.
    private static boolean ValidForDay(rateassign r, DateRange dRange) {
        if (r.Inactive != 0) return false;
        var dayStart = dRange.StartDate;
        var dayEnd = dRange.EndDate;
        var end = r.enddate != null ? r.enddate : MaxDate;
        return (!r.startdate.isAfter(dayStart) && end.isAfter(dayStart))
               || (!r.startdate.isBefore(dayStart) && r.startdate.isBefore(dayEnd));
    }
}
