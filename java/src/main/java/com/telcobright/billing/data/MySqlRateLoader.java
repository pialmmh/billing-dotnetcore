package com.telcobright.billing.data;

import com.telcobright.billing.mediation.engine.models.rateassign;
import com.telcobright.billing.mediation.rating.ratecaching.DateRange;
import com.telcobright.billing.mediation.rating.ratecaching.IRateLoader;
import com.telcobright.billing.mediation.rating.ratecaching.TupleByPeriod;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live {@link IRateLoader} over MySQL — the legacy RateCache one-day load (PopulateDicByDay +
 * RateDictionaryGeneratorByTuples + RateList) collapsed into one query path: read the rate-plan-assignment
 * tuples, then each tuple's day-valid rates, grouped by prefix and sorted desc by start date. Returns the
 * {@code DateRangeWiseRateDic[day]} shape (TupleByPeriod -&gt; prefix -&gt; List&lt;rateassign&gt;) the RateCache
 * caches and PrefixMatcher reads. The day-validity SQL mirrors the legacy temp_rate overlap predicate.
 *
 * <p>FAITHFUL-PORT NOTE (MySqlConnector -&gt; JDBC): the C# optional {@code MySqlTransaction? tx} param is
 * dropped — JDBC has no separate transaction object; statements created from the connection already run in
 * the connection's current transaction (the batch runner owns it via setAutoCommit(false)).</p>
 */
public final class MySqlRateLoader implements IRateLoader {
    private final Connection _conn;

    public MySqlRateLoader(Connection conn) {
        _conn = conn;
    }

    @Override
    public Map<TupleByPeriod, Map<String, List<rateassign>>> LoadDay(DateRange dRange) {
        try {
            var result = new HashMap<TupleByPeriod, Map<String, List<rateassign>>>();

            // {id, priority} pairs (C# read a List<(int Id, int Priority)>); materialised fully BEFORE the
            // per-tuple queries run, so only one ResultSet is open on the connection at a time.
            var tuples = new ArrayList<int[]>();
            try (Statement st = _conn.createStatement();
                 ResultSet r = st.executeQuery("select id, priority from rateplanassignmenttuple")) {
                while (r.next()) tuples.add(new int[]{r.getInt("id"), r.getInt("priority")});
            }

            for (var t : tuples) {
                int id = t[0];
                int priority = t[1];
                var rates = LoadRatesForTuple(id, dRange);
                if (rates.isEmpty()) continue;

                // RateListToDictionary: group by prefix, latest-start first.
                var byPrefix = new HashMap<String, List<rateassign>>();
                for (var rate : rates.stream()
                        .sorted(Comparator.comparing((rateassign x) -> x.startdate).reversed())
                        .toList()) {
                    var prefix = Integer.toString(rate.Prefix);
                    var list = byPrefix.get(prefix);
                    if (list == null) {
                        list = new ArrayList<>();
                        byPrefix.put(prefix, list);
                    }
                    list.add(rate);
                }

                var key = new TupleByPeriod();
                key.IdAssignmentTuple = id;
                key.DRange = dRange;
                key.Priority = priority;
                result.put(key, byPrefix);
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<rateassign> LoadRatesForTuple(int tupleId, DateRange dRange) throws SQLException {
        var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        var start = dRange.StartDate.format(fmt);
        var end = dRange.EndDate.format(fmt);
        var sql = "select id, Prefix, rateamount, Resolution, MinDurationSec, SurchargeTime, idrateplan,\n"
                + "                Category, SubCategory, startdate, enddate, Inactive, OtherAmount1, OtherAmount3,\n"
                + "                idrateplanassignmenttuple\n"
                + "            from rateassign\n"
                + "            where idrateplanassignmenttuple = " + tupleId + " and Inactive = 0 and (\n"
                + "                (startdate <= '" + start + "' and ifnull(enddate,'9999-12-31 23:59:59') > '" + start + "')\n"
                + "                or (startdate >= '" + start + "' and startdate < '" + end + "'))";

        var rates = new ArrayList<rateassign>();
        try (Statement st = _conn.createStatement();
             ResultSet r = st.executeQuery(sql)) {
            while (r.next()) rates.add(MapRate(r));
        }
        return rates;
    }

    private static rateassign MapRate(ResultSet r) throws SQLException {
        var rate = new rateassign();
        rate.id = r.getInt("id");
        rate.Prefix = r.getInt("Prefix");
        rate.rateamount = r.getBigDecimal("rateamount");
        rate.Resolution = r.getInt("Resolution");
        rate.MinDurationSec = r.getFloat("MinDurationSec");
        rate.SurchargeTime = r.getInt("SurchargeTime");
        long idrateplan = r.getLong("idrateplan");
        rate.idrateplan = r.wasNull() ? null : idrateplan;
        byte category = r.getByte("Category");
        rate.Category = r.wasNull() ? null : category;
        byte subCategory = r.getByte("SubCategory");
        rate.SubCategory = r.wasNull() ? null : subCategory;
        rate.startdate = r.getTimestamp("startdate").toLocalDateTime();
        var enddate = r.getTimestamp("enddate");
        rate.enddate = enddate == null ? null : enddate.toLocalDateTime();
        rate.Inactive = r.getInt("Inactive");
        float otherAmount1 = r.getFloat("OtherAmount1");
        rate.OtherAmount1 = r.wasNull() ? null : otherAmount1;
        float otherAmount3 = r.getFloat("OtherAmount3");
        rate.OtherAmount3 = r.wasNull() ? null : otherAmount3;
        int idtuple = r.getInt("idrateplanassignmenttuple");
        rate.idrateplanassignmenttuple = r.wasNull() ? null : idtuple;
        return rate;
    }
}
