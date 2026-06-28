using Billing.Mediation.Summary;
using MediationModel;
using MediationModel.enums;
using MySqlConnector;

namespace Billing.Data;

/// <summary>
/// The live <see cref="ISummaryStore"/> over MySQL (MySqlConnector). It runs on a caller-supplied
/// connection (and optional transaction) so it shares the single per-call connection the atomic write
/// uses. <c>LoadByStartTimes</c> is the PopulatePrevSummary load (SELECT the existing rows for the call's
/// bucketed start times); <c>ExecuteNonQuery</c> is the cache's write executor.
/// </summary>
public sealed class MySqlSummaryStore : ISummaryStore
{
    private readonly MySqlConnection _conn;
    private readonly MySqlTransaction? _tx;

    public MySqlSummaryStore(MySqlConnection conn, MySqlTransaction? tx = null)
    {
        _conn = conn;
        _tx = tx;
    }

    public int ExecuteNonQuery(string sql)
    {
        using var cmd = new MySqlCommand(sql, _conn, _tx);
        return cmd.ExecuteNonQuery();
    }

    public IReadOnlyList<AbstractCdrSummary> LoadByStartTimes(CdrSummaryType table, IReadOnlyCollection<DateTime> startTimes)
    {
        if (startTimes.Count == 0) return Array.Empty<AbstractCdrSummary>();

        var inList = string.Join(",", startTimes.Select(t => $"'{t:yyyy-MM-dd HH:mm:ss}'"));
        var sql = $"select {AbstractCdrSummary.ExtInsertColumns} from {table} where tup_starttime in ({inList})";

        using var cmd = new MySqlCommand(sql, _conn, _tx);
        using var reader = cmd.ExecuteReader();
        var rows = new List<AbstractCdrSummary>();
        while (reader.Read())
            rows.Add(MapRow(table, reader));
        return rows;
    }

    private static AbstractCdrSummary MapRow(CdrSummaryType table, MySqlDataReader r)
    {
        AbstractCdrSummary s = table switch
        {
            CdrSummaryType.sum_voice_day_02 => new sum_voice_day_02(),
            CdrSummaryType.sum_voice_day_03 => new sum_voice_day_03(),
            CdrSummaryType.sum_voice_hr_02 => new sum_voice_hr_02(),
            CdrSummaryType.sum_voice_hr_03 => new sum_voice_hr_03(),
            _ => throw new NotSupportedException($"No summary entity mapped for {table}."),
        };

        s.id = L(r, "id");
        s.tup_switchid = I(r, "tup_switchid");
        s.tup_inpartnerid = I(r, "tup_inpartnerid");
        s.tup_outpartnerid = I(r, "tup_outpartnerid");
        s.tup_incomingroute = S(r, "tup_incomingroute");
        s.tup_outgoingroute = S(r, "tup_outgoingroute");
        s.tup_customerrate = D(r, "tup_customerrate");
        s.tup_supplierrate = D(r, "tup_supplierrate");
        s.tup_incomingip = S(r, "tup_incomingip");
        s.tup_outgoingip = S(r, "tup_outgoingip");
        s.tup_countryorareacode = S(r, "tup_countryorareacode");
        s.tup_matchedprefixcustomer = S(r, "tup_matchedprefixcustomer");
        s.tup_matchedprefixsupplier = S(r, "tup_matchedprefixsupplier");
        s.tup_sourceId = S(r, "tup_sourceId");
        s.tup_destinationId = S(r, "tup_destinationId");
        s.tup_customercurrency = S(r, "tup_customercurrency");
        s.tup_suppliercurrency = S(r, "tup_suppliercurrency");
        s.tup_tax1currency = S(r, "tup_tax1currency");
        s.tup_tax2currency = S(r, "tup_tax2currency");
        s.tup_vatcurrency = S(r, "tup_vatcurrency");
        s.tup_starttime = Dt(r, "tup_starttime");
        s.totalcalls = L(r, "totalcalls");
        s.connectedcalls = L(r, "connectedcalls");
        s.connectedcallsCC = L(r, "connectedcallsCC");
        s.successfulcalls = L(r, "successfulcalls");
        s.actualduration = D(r, "actualduration");
        s.roundedduration = D(r, "roundedduration");
        s.duration1 = D(r, "duration1");
        s.duration2 = D(r, "duration2");
        s.duration3 = D(r, "duration3");
        s.PDD = D(r, "PDD");
        s.customercost = D(r, "customercost");
        s.suppliercost = D(r, "suppliercost");
        s.tax1 = D(r, "tax1");
        s.tax2 = D(r, "tax2");
        s.vat = D(r, "vat");
        s.intAmount1 = I(r, "intAmount1");
        s.intAmount2 = I(r, "intAmount2");
        s.longAmount1 = L(r, "longAmount1");
        s.longAmount2 = L(r, "longAmount2");
        s.longDecimalAmount1 = D(r, "longDecimalAmount1");
        s.longDecimalAmount2 = D(r, "longDecimalAmount2");
        s.intAmount3 = I(r, "intAmount3");
        s.longAmount3 = L(r, "longAmount3");
        s.longDecimalAmount3 = D(r, "longDecimalAmount3");
        s.decimalAmount1 = D(r, "decimalAmount1");
        s.decimalAmount2 = D(r, "decimalAmount2");
        s.decimalAmount3 = D(r, "decimalAmount3");
        return s;
    }

    private static long L(MySqlDataReader r, string c) => Convert.ToInt64(r[c]);
    private static int I(MySqlDataReader r, string c) => Convert.ToInt32(r[c]);
    private static decimal D(MySqlDataReader r, string c) => Convert.ToDecimal(r[c]);
    private static DateTime Dt(MySqlDataReader r, string c) => Convert.ToDateTime(r[c]);
    private static string S(MySqlDataReader r, string c) => r[c] is DBNull ? "" : Convert.ToString(r[c]) ?? "";
}
