using LibraryExtensions;
using MediationModel;
using TelcobrightMediation;

namespace Billing.Tests;

/// <summary>The legacy PrefixMatcher over the per-day RateCache: priority across tuples (outer),
/// longest-prefix within a tuple (middle), Category/SubCategory + [startdate, enddate) validity (inner);
/// first match wins. The cache is fed by TupleRateLoader, so this also exercises the config-driven day load
/// (future/expired rates are filtered out by the day-overlap predicate before the matcher even runs).</summary>
public class PrefixMatcherTests
{
    private static readonly DateTime Now = new(2026, 6, 19);
    private static DateRange Day => new(Now.Date, Now.Date.AddDays(1));

    // Match a number through a fresh RateCache built over the given tuples (each tuple's nested rateassigns
    // become its per-day prefix dictionary, exactly as in the live config-driven flow).
    private static rateassign? Match(string number, int category, int subCategory,
        params rateplanassignmenttuple[] tuples)
    {
        var cache = new RateCache(new TupleRateLoader(tuples));
        var tups = tuples
            .Select(t => new TupleByPeriod { IdAssignmentTuple = t.id, DRange = Day, Priority = t.priority })
            .ToList();
        return new PrefixMatcher(cache, number, category, subCategory, tups, Now).MatchPrefix();
    }

    [Fact]
    public void Longest_prefix_wins_within_a_tuple()
    {
        var tup = TestData.Tup(10, 1, 5, null, 0,
            TestData.Ra(1, 1.0m), TestData.Ra(17, 2.0m), TestData.Ra(171, 3.0m));

        Assert.Equal(171, Match("1712345", 1, 1, tup)!.Prefix);   // longest (171)
        Assert.Equal(17, Match("1799999", 1, 1, tup)!.Prefix);    // 17 (no 171 match)
        Assert.Equal(1, Match("1500000", 1, 1, tup)!.Prefix);     // 1
        Assert.Null(Match("9000000", 1, 1, tup));                  // none
    }

    [Fact]
    public void Lowest_priority_tuple_wins()
    {
        var hi = TestData.Tup(10, 1, 5, null, 5, TestData.Ra(1, 10.0m));
        var lo = TestData.Tup(10, 1, 5, null, 0, TestData.Ra(1, 5.0m));

        Assert.Equal(5.0m, Match("1234", 1, 1, hi, lo)!.rateamount);   // priority-0 tuple wins
    }

    [Fact]
    public void Category_or_subcategory_mismatch_does_not_match()
    {
        var tup = TestData.Tup(10, 1, 5, null, 0, TestData.Ra(1, 1.0m, category: 2));
        Assert.Null(Match("1234", 1, 1, tup));
    }

    [Fact]
    public void Outside_validity_window_does_not_match()
    {
        var future = TestData.Ra(1, 1.0m, startdate: new DateTime(2027, 1, 1));
        var expired = TestData.Ra(2, 1.0m, enddate: new DateTime(2025, 1, 1));
        var tup = TestData.Tup(10, 1, 5, null, 0, future, expired);

        Assert.Null(Match("1234", 1, 1, tup));   // not yet effective
        Assert.Null(Match("2345", 1, 1, tup));   // expired
    }
}
