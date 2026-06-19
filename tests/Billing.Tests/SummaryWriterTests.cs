using MediationModel;

namespace Billing.Tests;

/// <summary>The verbatim-ported AbstractCdrSummary SQL writers (GetExtInsertValues / GetUpdateCommand):
/// numbers as-is, dates/strings quoted, null strings -> '', the table-name placeholder for substitution.</summary>
public class SummaryWriterTests
{
    [Fact]
    public void Insert_values_render_numbers_dates_and_strings()
    {
        var s = new sum_voice_day_03
        {
            id = 5, tup_switchid = 1, tup_starttime = new DateTime(2026, 6, 19, 14, 0, 0),
            customercost = 1.5m, totalcalls = 2, tup_countryorareacode = "880",
            // tup_* string fields left null -> '' via ToNotNullSqlField (no NRE)
        };

        var insert = s.GetExtInsertValues().ToString();
        Assert.StartsWith("(5,1,", insert);                      // id, switchid
        Assert.Contains("'2026-06-19 14:00:00'", insert);        // tup_starttime quoted
        Assert.Contains("'880'", insert);                        // country code quoted
        Assert.Contains("1.5", insert);                          // customercost
        Assert.Contains("''", insert);                           // a null string field -> ''
        Assert.EndsWith(")", insert);
    }

    [Fact]
    public void Update_command_sets_aggregates_and_keeps_the_table_placeholder()
    {
        var s = new sum_voice_day_03 { id = 5, totalcalls = 2, customercost = 1.5m };

        var update = s.GetUpdateCommand(x => $"where id={x.id}").ToString();
        Assert.Contains("update AbstractCdrSummary set", update);   // placeholder the context replaces
        Assert.Contains("totalcalls=2", update);
        Assert.Contains("customercost=1.5", update);
        Assert.Contains("where id=5", update);
    }

    [Fact]
    public void Insert_columns_list_count_matches_the_value_count()
    {
        var columns = AbstractCdrSummary.ExtInsertColumns.Split(',').Length;
        var values = new sum_voice_day_03().GetExtInsertValues().ToString().Split(',').Length;
        Assert.Equal(columns, values);   // header column count == value tuple field count
    }
}
