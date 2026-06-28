using LibraryExtensions;
using MediationModel;
using MySqlConnector;
using TelcobrightMediation;

namespace Billing.Data;

/// <summary>
/// The live <see cref="IRateLoader"/> over MySQL — the legacy RateCache one-day load (PopulateDicByDay +
/// RateDictionaryGeneratorByTuples + RateList) collapsed into one query path: read the rate-plan-assignment
/// tuples, then each tuple's day-valid rates, grouped by prefix and sorted desc by start date. Returns the
/// <c>DateRangeWiseRateDic[day]</c> shape (TupleByPeriod → prefix → List&lt;rateassign&gt;) the RateCache
/// caches and PrefixMatcher reads. The day-validity SQL mirrors the legacy temp_rate overlap predicate.
/// </summary>
public sealed class MySqlRateLoader : IRateLoader
{
    private readonly MySqlConnection _conn;
    private readonly MySqlTransaction? _tx;

    public MySqlRateLoader(MySqlConnection conn, MySqlTransaction? tx = null)
    {
        _conn = conn;
        _tx = tx;
    }

    public Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>> LoadDay(DateRange dRange)
    {
        var result = new Dictionary<TupleByPeriod, Dictionary<string, List<rateassign>>>(new TupleByPeriod.EqualityComparer());

        var tuples = new List<(int Id, int Priority)>();
        using (var cmd = new MySqlCommand("select id, priority from rateplanassignmenttuple", _conn, _tx))
        using (var r = cmd.ExecuteReader())
            while (r.Read()) tuples.Add((Convert.ToInt32(r["id"]), Convert.ToInt32(r["priority"])));

        foreach (var (id, priority) in tuples)
        {
            var rates = LoadRatesForTuple(id, dRange);
            if (rates.Count == 0) continue;

            // RateListToDictionary: group by prefix, latest-start first.
            var byPrefix = new Dictionary<string, List<rateassign>>();
            foreach (var rate in rates.OrderByDescending(x => x.startdate))
            {
                var prefix = rate.Prefix.ToString();
                if (!byPrefix.TryGetValue(prefix, out var list)) byPrefix[prefix] = list = new List<rateassign>();
                list.Add(rate);
            }

            result[new TupleByPeriod { IdAssignmentTuple = id, DRange = dRange, Priority = priority }] = byPrefix;
        }
        return result;
    }

    private List<rateassign> LoadRatesForTuple(int tupleId, DateRange dRange)
    {
        var start = dRange.StartDate.ToString("yyyy-MM-dd HH:mm:ss");
        var end = dRange.EndDate.ToString("yyyy-MM-dd HH:mm:ss");
        var sql = $@"select id, Prefix, rateamount, Resolution, MinDurationSec, SurchargeTime, idrateplan,
                Category, SubCategory, startdate, enddate, Inactive, OtherAmount1, OtherAmount3,
                idrateplanassignmenttuple
            from rateassign
            where idrateplanassignmenttuple = {tupleId} and Inactive = 0 and (
                (startdate <= '{start}' and ifnull(enddate,'9999-12-31 23:59:59') > '{start}')
                or (startdate >= '{start}' and startdate < '{end}'))";

        var rates = new List<rateassign>();
        using var cmd = new MySqlCommand(sql, _conn, _tx);
        using var r = cmd.ExecuteReader();
        while (r.Read()) rates.Add(MapRate(r));
        return rates;
    }

    private static rateassign MapRate(MySqlDataReader r) => new()
    {
        id = Convert.ToInt32(r["id"]),
        Prefix = Convert.ToInt32(r["Prefix"]),
        rateamount = Convert.ToDecimal(r["rateamount"]),
        Resolution = Convert.ToInt32(r["Resolution"]),
        MinDurationSec = Convert.ToSingle(r["MinDurationSec"]),
        SurchargeTime = Convert.ToInt32(r["SurchargeTime"]),
        idrateplan = r["idrateplan"] is DBNull ? null : Convert.ToInt64(r["idrateplan"]),
        Category = r["Category"] is DBNull ? null : Convert.ToSByte(r["Category"]),
        SubCategory = r["SubCategory"] is DBNull ? null : Convert.ToSByte(r["SubCategory"]),
        startdate = Convert.ToDateTime(r["startdate"]),
        enddate = r["enddate"] is DBNull ? null : Convert.ToDateTime(r["enddate"]),
        Inactive = Convert.ToInt32(r["Inactive"]),
        OtherAmount1 = r["OtherAmount1"] is DBNull ? null : Convert.ToSingle(r["OtherAmount1"]),
        OtherAmount3 = r["OtherAmount3"] is DBNull ? null : Convert.ToSingle(r["OtherAmount3"]),
        idrateplanassignmenttuple = r["idrateplanassignmenttuple"] is DBNull ? null : Convert.ToInt32(r["idrateplanassignmenttuple"]),
    };
}
